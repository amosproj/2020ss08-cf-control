package cloud.foundry.cli.operations;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import cloud.foundry.cli.crosscutting.logging.Log;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.exceptions.CreationException;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationManifestBean;
import org.cloudfoundry.client.v3.Metadata;
import org.cloudfoundry.client.v3.applications.UpdateApplicationRequest;
import org.cloudfoundry.client.v3.applications.UpdateApplicationResponse;

import org.cloudfoundry.client.v3.applications.GetApplicationRequest;
import org.cloudfoundry.client.v3.applications.GetApplicationResponse;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.*;

import org.cloudfoundry.operations.services.BindServiceInstanceRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles the operations for querying and manipulating applications on a cloud
 * foundry instance.
 *
 * To retrieve the data from resulting Mono or Flux objects you can use
 * subscription methods (block, subscribe, etc.) provided by the reactor
 * library. For more details on how to work with Mono's visit:
 * https://projectreactor.io/docs/core/release/reference/index.html#core-features
 */
public class ApplicationsOperations extends AbstractOperations<DefaultCloudFoundryOperations> {

    private static final Log log = Log.getLog(ApplicationsOperations.class);

    /**
     * Name of the environment variable that hold the docker password.
     */
    private static final String DOCKER_PASSWORD_VAR_NAME = "CF_DOCKER_PASSWORD";

    public ApplicationsOperations(DefaultCloudFoundryOperations cloudFoundryOperations) {
        super(cloudFoundryOperations);
    }

    /**
     * Prepares a request for fetching applications data from the cloud foundry
     * instance. The resulting mono will not perform any logging by default.
     *
     * @return mono object of all applications as map of the application names as
     *         key and the ApplicationBeans as value
     */
    public Mono<Map<String, ApplicationBean>> getAll() {
        return this.cloudFoundryOperations
            .applications()
            .list()
            // group the application and the metadata in pairs
            .flatMap(applicationSummary -> Flux.zip(
                getApplicationManifest(applicationSummary),
                getMetadata(applicationSummary)))
            // T1 is the ApplicationManifest and T2 is the metadata of the application
            .collectMap(tuple -> tuple.getT1().getName(),
                tuple -> new ApplicationBean(tuple.getT1(), tuple.getT2()));
    }

    private Mono<ApplicationManifest> getApplicationManifest(ApplicationSummary applicationSummary) {
        return this.cloudFoundryOperations
            .applications()
            .getApplicationManifest(GetApplicationManifestRequest
                .builder()
                .name(applicationSummary.getName())
                .build());
    }

    private Mono<Metadata> getMetadata(ApplicationSummary applicationSummary) {
        GetApplicationRequest request = GetApplicationRequest.builder()
            .applicationId(applicationSummary.getId())
            .build();
        return this.cloudFoundryOperations.getCloudFoundryClient()
            .applicationsV3()
            .get(request)
            .map(GetApplicationResponse::getMetadata);
    }

    /**
     * Prepares a request for deleting a specific application associated with the
     * provided name. The resulting mono is preconfigured such that it will perform
     * logging.
     *
     * @param applicationName applicationName Name of an application.
     * @throws NullPointerException when the applicationName is null
     * @return mono which can be subscribed on to trigger the removal of the app.
     *         The mono also handles the logging.
     */
    public Mono<Void> remove(String applicationName) {
        checkNotNull(applicationName);

        DeleteApplicationRequest request = DeleteApplicationRequest
            .builder()
            .name(applicationName)
            .build();

        return this.cloudFoundryOperations.applications()
            .delete(request)
            .doOnSuccess(aVoid -> log.info("App removed: ", applicationName))
            .onErrorStop();
    }

    /**
     * Prepares a request for pushing an app to the cloud foundry instance specified
     * within the cloud foundry operations instance. The resulting mono is
     * preconfigured such that it will perform logging.
     *
     * @param appName     name of the application
     * @param bean        application bean that holds the configuration settings to
     *                    deploy the app to the cloud foundry instance
     * @param shouldStart if the app should start after being created
     * @throws NullPointerException     when bean or app name is null or docker
     *                                  password was not set in environment
     *                                  variables when creating app via dockerImage
     *                                  and docker credentials
     * @throws IllegalArgumentException when app name empty
     * @throws CreationException        when any fatal error occurs during creation
     *                                  of the app
     * @throws SecurityException        when there is no permission to access
     *                                  environment variable CF_DOCKER_PASSWORD
     * @return mono which can be subscribed on to trigger the creation of the app
     */
    public Mono<Void> create(String appName, ApplicationBean bean, boolean shouldStart) {
        checkNotNull(appName, "Application name cannot be null");
        checkArgument(!appName.isEmpty(), "Application name cannot be empty");
        checkNotNull(bean, "Application contents cannot be null");

        try {
            return doCreate(appName, bean, shouldStart);
        } catch (RuntimeException e) {
            throw new CreationException(e);
        }
    }

    private Mono<Void> doCreate(String appName, ApplicationBean bean, boolean shouldStart) {
        return this.cloudFoundryOperations
                .applications()
                .pushManifest(PushApplicationManifestRequest
                        .builder()
                        .manifest(buildApplicationManifest(appName, bean))
                        .noStart(!shouldStart)
                        .build())
                .onErrorContinue(this::whenServiceNotFound, log::warning)
                .doOnSubscribe(subscription -> {
                    log.debug("Create app:", appName);
                    log.debug("Bean of the app:", bean);
                    log.debug("Should the app start:", shouldStart);
                })
                .doOnSuccess(aVoid -> log.info("App created:", appName))
                .then(getApplicationDetail(appName)
                        .flatMap(applicationDetail -> updateAppMeta(applicationDetail.getId(), bean)))
                .doOnError(throwable -> log.warning(throwable, "test"))
                .onErrorStop()
                .then();
    }

    private boolean whenServiceNotFound(Throwable throwable) {
        return throwable instanceof IllegalArgumentException
            && throwable.getMessage().contains("Service instance")
            && throwable.getMessage().contains("could not be found");
    }

    private ApplicationManifest buildApplicationManifest(String appName, ApplicationBean bean) {
        if (bean.getManifest() == null) {
            bean.setManifest(new ApplicationManifestBean());
        }

        return ApplicationManifest.builder()
            .name(appName)
            .path(bean.getPath() == null ? null : Paths.get(bean.getPath()))
            .buildpack(bean.getManifest().getBuildpack())
            .command(bean.getManifest().getCommand())
            .disk(bean.getManifest().getDisk())
            .docker(Docker.builder()
                .image(bean.getPath() == null && bean.getManifest().getDockerImage() == null
                    ? ""
                    : bean.getManifest().getDockerImage())
                .username(bean.getManifest().getDockerUsername())
                .password(getDockerPassword(bean.getManifest()))
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
            .services(bean.getManifest().getServices())
            .build();
    }

    private String getDockerPassword(ApplicationManifestBean bean) {
        if (bean.getDockerImage() == null || bean.getDockerUsername() == null) {
            return null;
        }

        // TODO: Maybe outsource retrieving env variables to a dedicated class in a future feature.
        String password = System.getenv(DOCKER_PASSWORD_VAR_NAME);
        if (password == null) {
            throw new NullPointerException("Docker password is not set in environment variable: "
                + DOCKER_PASSWORD_VAR_NAME);
        }
        return password;
    }

    private List<Route> getAppRoutes(List<String> routes) {
        return routes == null ? null
            : routes
                .stream()
                .filter(Objects::nonNull)
                .map(route -> Route.builder().route(route).build())
                .collect(Collectors.toList());
    }

    private Mono<ApplicationDetail> getApplicationDetail(String appName) {
        return this.cloudFoundryOperations
                .applications()
                .get(org.cloudfoundry.operations.applications.GetApplicationRequest.builder().name(appName).build())
                .doOnSubscribe(subscription -> log.debug("Getting app detail for app: " + appName));
    }

    private Mono<UpdateApplicationResponse> updateAppMeta(String appId, ApplicationBean applicationBean) {
        return this.cloudFoundryOperations
                .getCloudFoundryClient()
                .applicationsV3()
                .update(UpdateApplicationRequest.builder()
                        .applicationId(appId)
                        .metadata(Metadata.builder()
                                .annotation(ApplicationBean.METADATA_KEY, applicationBean.getMeta())
                                .annotation(ApplicationBean.PATH_KEY, applicationBean.getPath())
                                .build()).build())
                .doOnSubscribe(subscription -> log.debug("Update app meta for app: " + appId));
    }

    /**
     * Prepares a request for renaming an application instance.
     * The resulting mono is preconfigured such that it will perform logging.
     *
     * @param newName     new name of the application instance
     * @param currentName current name of the application instance
     * @return mono which can be subscribed on to trigger the renaming request to the cf instance
     * @throws NullPointerException when one of the arguments was null
     */
    public Mono<Void> rename(String newName, String currentName) {
        checkNotNull(newName);
        checkNotNull(currentName);

        RenameApplicationRequest renameApplicationRequest = RenameApplicationRequest.builder()
                .name(currentName)
                .newName(newName)
                .build();

        return this.cloudFoundryOperations.applications().rename(renameApplicationRequest)
                .doOnSubscribe(aVoid -> {
                    log.debug("Rename application:", currentName);
                    log.debug("With new name:", newName); })
                .doOnSuccess(aVoid -> log.info("Application renamed from", currentName, "to", newName));
    }

    /**
     * Prepares a request for scaling properties of an application instance.
     * The resulting mono is preconfigured such that it will perform logging.
     *
     * @param applicationName the name of the application to scale
     * @param diskLimit the new disk limit
     * @param memoryLimit the new memory limit
     * @param instances the new number of instances
     * @return mono which can be subscribed on to trigger the scale request to the cf instance
     * @throws NullPointerException if the provided application name is null
     */
    public Mono<Void> scale(String applicationName, Integer diskLimit, Integer memoryLimit, Integer instances) {
        checkNotNull(applicationName);

        ScaleApplicationRequest scaleRequest = ScaleApplicationRequest.builder()
                .name(applicationName)
                .diskLimit(diskLimit)
                .memoryLimit(memoryLimit)
                .instances(instances)
                .build();

        return cloudFoundryOperations.applications().scale(scaleRequest)
                .doOnSubscribe(aVoid -> {
                    log.debug("Scale app:", applicationName);
                    log.debug("With new disk limit:", diskLimit);
                    log.debug("With new memory limit:", memoryLimit);
                    log.debug("With new number of instances:", instances); })
                .doOnSuccess(aVoid -> log.info("Application", applicationName, "was scaled"));
    }

    /**
     * Prepares a request for adding an environment variable to an application instance.
     * The resulting mono is preconfigured such that it will perform logging.
     *
     * @param applicationName the name of the application to add the environment variable for
     * @param variableName the name of the environment variable to add
     * @param variableValue the value of the environment variable to add
     * @return mono which can be subscribed on to trigger the environment variable request to the cf instance
     * @throws NullPointerException if any of the arguments are null
     */
    public Mono<Void> addEnvironmentVariable(String applicationName, String variableName, String variableValue) {
        checkNotNull(applicationName);
        checkNotNull(variableName);
        checkNotNull(variableValue);

        SetEnvironmentVariableApplicationRequest addEnvVarRequest = SetEnvironmentVariableApplicationRequest.builder()
                .name(applicationName)
                .variableName(variableName)
                .variableValue(variableValue)
                .build();

        return cloudFoundryOperations.applications().setEnvironmentVariable(addEnvVarRequest)
                .doOnSubscribe(aVoid -> {
                    log.debug("Added environment variable for app:", applicationName);
                    log.debug("With variable name:", variableName);
                    log.debug("With variable value:", variableValue); })
                .doOnSuccess(aVoid -> log.info("Environment variable", variableName, " with value",
                        variableValue , "was added to the app", applicationName));
    }

    /**
     * Prepares a request for removing an environment variable from an application instance.
     * The resulting mono is preconfigured such that it will perform logging.
     *
     * @param applicationName the name of the application to remove the environment variable of
     * @param variableName the name of the environment variable to remove
     * @return mono which can be subscribed on to trigger the environment variable request to the cf instance
     * @throws NullPointerException if any of the arguments are null
     */
    public Mono<Void> removeEnvironmentVariable(String applicationName, String variableName) {
        checkNotNull(applicationName);
        checkNotNull(variableName);

        UnsetEnvironmentVariableApplicationRequest removeEnvVarRequest = UnsetEnvironmentVariableApplicationRequest
                .builder()
                .name(applicationName)
                .variableName(variableName)
                .build();

        return cloudFoundryOperations.applications().unsetEnvironmentVariable(removeEnvVarRequest)
                .doOnSubscribe(aVoid -> {
                    log.debug("Removed environment variable for app:", applicationName);
                    log.debug("With variable name:", variableName); })
                .doOnSuccess(aVoid -> log.info("Environment variable", variableName,
                            "was removed from the app", applicationName));
    }

    /**
     * Prepares a request for setting the type of the health check of an application instance.
     * The resulting mono is preconfigured such that it will perform logging.
     *
     * @param applicationName the name of the application to set the health check type of
     * @param healthCheckType the health check type to set
     * @return mono which can be subscribed on to trigger the health check type request to the cf instance
     * @throws NullPointerException if any of the arguments are null
     */
    public Mono<Void> setHealthCheck(String applicationName, ApplicationHealthCheck healthCheckType) {
        checkNotNull(applicationName);
        checkNotNull(healthCheckType);

        SetApplicationHealthCheckRequest setHealthCheckRequest = SetApplicationHealthCheckRequest.builder()
                .name(applicationName)
                .type(healthCheckType)
                .build();

        return cloudFoundryOperations.applications().setHealthCheck(setHealthCheckRequest)
                .doOnSubscribe(aVoid -> {
                    log.debug("Set health check type for app:", applicationName);
                    log.debug("With health check type:", healthCheckType); })
                .doOnSuccess(aVoid -> log.info("The health check type of the app", applicationName,
                        "was set to", healthCheckType));
    }

    /**
     * Prepares a request for binding an app to a service.
     * The resulting mono is preconfigured such that it will perform logging.
     *
     * @param applicationName the app that should be bound to the service
     * @param serviceName the service to which the app should be bound
     * @return mono which can be subscribed on to trigger the app binding
     * @throws NullPointerException if any of the arguments is null
     */
    public Mono<Void> bindToService(String applicationName, String serviceName) {
        checkNotNull(applicationName);
        checkNotNull(serviceName);

        BindServiceInstanceRequest bindServiceRequest = BindServiceInstanceRequest.builder()
                .applicationName(applicationName)
                .serviceInstanceName(serviceName)
                .build();

        return cloudFoundryOperations.services().bind(bindServiceRequest);
    }
}
