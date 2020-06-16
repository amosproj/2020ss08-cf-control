package cloud.foundry.cli.operations;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.exceptions.CreationException;
import cloud.foundry.cli.crosscutting.logging.Log;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.Docker;
import org.cloudfoundry.operations.applications.GetApplicationManifestRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.operations.applications.Route;
import org.cloudfoundry.operations.services.DeleteServiceInstanceRequest;
import org.cloudfoundry.operations.services.GetServiceInstanceRequest;
import org.cloudfoundry.operations.services.ServiceInstance;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * Handles the operations for manipulating applications on a cloud foundry instance.
 */
public class ApplicationsOperations extends AbstractOperations<DefaultCloudFoundryOperations> {

    /**
     * Name of the environment variable that hold the docker password.
     */
    private static final String DOCKER_PASSWORD_VAR_NAME = "CF_DOCKER_PASSWORD";

    public ApplicationsOperations(DefaultCloudFoundryOperations cloudFoundryOperations) {
        super(cloudFoundryOperations);
    }

    /**
     * This method fetches applications data from the cloud foundry instance.
     * To retrieve data given by the Mono object you can use the subscription methods (block, subscribe, etc.) provided
     * by the reactor library.
     * For more details on how to work with Mono's visit:
     * https://projectreactor.io/docs/core/release/reference/index.html#core-features
     *
     * @return Mono object of all applications as list of ApplicationBeans
     */
    public Mono<Map<String, ApplicationBean>> getAll() {
        return this.cloudFoundryOperations
                .applications()
                .list()
                .flatMap(this::getApplicationManifest)
                .collectMap(ApplicationManifest::getName, ApplicationBean::new);
    }

    private Mono<ApplicationManifest> getApplicationManifest(ApplicationSummary applicationSummary) {
        return this.cloudFoundryOperations
                .applications()
                .getApplicationManifest(GetApplicationManifestRequest
                        .builder()
                        .name(applicationSummary.getName())
                        .build());
    }

    /**
     * Deletes a specific application associated with the name <code>applicationName</code>.
     *
     * @param applicationName applicationName Name of an application.
     */
    public void removeApplication(String applicationName) {
        DeleteApplicationRequest request = DeleteApplicationRequest
                .builder()
                .name(applicationName)
                .build();
        try {
            this.cloudFoundryOperations.applications().delete(request).block();
            Log.info("Application " + applicationName + " has been successfully removed.");
        } catch (Exception e) {
            Log.error(e.getMessage());
        }
    }

    /**
     * Pushes the app to the cloud foundry instance specified within the cloud foundry operations instance
     *
     * @param appName     name of the application
     * @param bean        application bean that holds the configuration settings to deploy the app
     *                    to the cloud foundry instance
     * @param shouldStart if the app should start after being created
     * @throws NullPointerException     when bean or app name is null
     *                                  or docker password was not set in environment variables when creating app via
     *                                  dockerImage and docker credentials
     * @throws IllegalArgumentException when neither a path nor a docker image were specified, or app name empty
     * @throws CreationException        when app already exists
     *                                  or any fatal error occurs during creation of the app
     * @throws SecurityException        when there is no permission to access environment variable CF_DOCKER_PASSWORD
     */
    public void create(String appName, ApplicationBean bean, boolean shouldStart) throws CreationException {
        checkNotNull(appName);
        checkArgument(!appName.isEmpty(), "empty name");
        checkNotNull(bean);

        // useful, otherwise cloud foundry operations library might behave in a weird way
        // path null + docker image null => NullPointer Exception that is not intuitive
        // and when setting docker image to empty string to prevent this
        // can lead to clash when path and buildpack was set
        checkIfPathOrDockerGiven(bean);

        // this check is important, otherwise an app could get overwritten
        if (appExists(appName)) {
            throw new CreationException("app exists already");
        }

        try {
            Log.debug("Create app:", appName);
            Log.debug("Bean of the app:", bean);
            Log.debug("Should the app start:", shouldStart);

            doCreate(appName, bean, shouldStart);
        } catch (RuntimeException e) {
            Log.debug("Clean up the app you tried to create");
            removeApplication(appName);
            throw new CreationException(e);
        }
    }

    private void doCreate(String appName, ApplicationBean bean, boolean shouldStart) {
        this.cloudFoundryOperations
                .applications()
                .pushManifest(PushApplicationManifestRequest
                        .builder()
                        .manifest(buildApplicationManifest(appName, bean))
                        .noStart(!shouldStart)
                        .build())
                //TODO: replace this error handling with a more precise one in a future release, works for now
                // Cloud Foundry Operations Library Throws either IllegalArgumentException or IllegalStateException.
                .onErrorContinue(throwable -> throwable instanceof IllegalArgumentException
                                //Fatal errors, exclude them.
                                && !throwable.getMessage().contains("Application")
                                && !throwable.getMessage().contains("Stack"),
                        (throwable, o) -> Log.warning(throwable.getMessage()))
                //Error when staging or starting. So don't throw error, only log error.
                .onErrorContinue(throwable -> throwable instanceof IllegalStateException,
                        (throwable, o) -> Log.warning(throwable.getMessage()))
                .block();
    }

    private ApplicationManifest buildApplicationManifest(String appName, ApplicationBean bean) {
        ApplicationManifest.Builder builder = ApplicationManifest.builder();

        builder
                .name(appName)
                .path(bean.getPath() == null ? null : Paths.get(bean.getPath()));

        if (bean.getManifest() != null) {
            builder.buildpack(bean.getManifest().getBuildpack())
                    .command(bean.getManifest().getCommand())
                    .disk(bean.getManifest().getDisk())
                    .docker(Docker.builder()
                            .image(bean.getManifest().getDockerImage())
                            .username(bean.getManifest().getDockerUsername())
                            .password(getDockerPassword(bean))
                            .build())
                    .healthCheckHttpEndpoint(bean.getManifest().getHealthCheckHttpEndpoint())
                    .healthCheckType(bean.getManifest().getHealthCheckType())
                    .instances(bean.getManifest().getInstances())
                    .memory(bean.getManifest().getMemory())
                    .noRoute(bean.getManifest().getNoRoute())
                    .routePath(bean.getManifest().getRoutePath())
                    .randomRoute(bean.getManifest().getRandomRoute())
                    .routes(getAppRoutes(bean.getManifest().getRoutes()))
                    .stack(bean.getManifest().getStack())
                    .timeout(bean.getManifest().getTimeout())
                    .putAllEnvironmentVariables(Optional.ofNullable(bean.getManifest().getEnvironmentVariables())
                            .orElse(Collections.emptyMap()))
                    .services(bean.getManifest().getServices());
        }

        return builder.build();
    }

    private String getDockerPassword(ApplicationBean bean) {
        if (bean.getManifest().getDockerImage() == null || bean.getManifest().getDockerUsername() == null) {
            return null;
        }

        //TODO: Maybe outsource retrieving env variables to a dedicated class in a future feature.
        String password = System.getenv(DOCKER_PASSWORD_VAR_NAME);
        if (password == null) {
            throw new NullPointerException("Docker password is not set in environment variable: "
                    + DOCKER_PASSWORD_VAR_NAME);
        }
        return password;
    }

    private List<Route> getAppRoutes(List<String> routes) {
        return routes == null ? null : routes
                .stream()
                .filter(Objects::nonNull)
                .map(route -> Route.builder().route(route).build())
                .collect(Collectors.toList());
    }

    /**
     * assertion method
     */
    private boolean appExists(String name) {
        // If app does not exist an IllegalArgumentException will be thrown.
        try {
            this.cloudFoundryOperations
                    .applications()
                    .get(GetApplicationRequest
                            .builder()
                            .name(name)
                            .build())
                    .block();
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    /**
     * assertion method
     */
    private void checkIfPathOrDockerGiven(ApplicationBean bean) {
        String message = "app path or docker image must be given";
        if (bean.getPath() == null && bean.getManifest() == null) {
            throw new IllegalArgumentException(message);
        } else if (bean.getPath() == null
                && bean.getManifest() != null
                && bean.getManifest().getDockerImage() == null) {
            throw new IllegalArgumentException(message);
        }
    }

}