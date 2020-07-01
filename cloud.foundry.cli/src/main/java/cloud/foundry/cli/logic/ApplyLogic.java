package cloud.foundry.cli.logic;

import static com.google.common.base.Preconditions.checkNotNull;

import cloud.foundry.cli.crosscutting.logging.Log;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.mapping.beans.ConfigBean;
import cloud.foundry.cli.crosscutting.mapping.beans.ServiceBean;
import cloud.foundry.cli.crosscutting.mapping.beans.SpecBean;
import cloud.foundry.cli.crosscutting.exceptions.ApplyException;
import cloud.foundry.cli.logic.apply.ApplicationRequestsPlaner;
import cloud.foundry.cli.logic.apply.ServiceRequestsPlaner;
import cloud.foundry.cli.logic.apply.SpaceDevelopersRequestsPlaner;
import cloud.foundry.cli.logic.diff.DiffResult;
import cloud.foundry.cli.logic.diff.change.CfChange;
import cloud.foundry.cli.logic.diff.change.container.CfContainerChange;
import cloud.foundry.cli.operations.ApplicationsOperations;
import cloud.foundry.cli.operations.ServicesOperations;
import cloud.foundry.cli.operations.SpaceDevelopersOperations;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import reactor.core.publisher.Flux;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * This class takes care of applying desired cloud foundry configurations to a
 * live system.
 */
public class ApplyLogic {

    private static final Log log = Log.getLog(ApplyLogic.class);

    private DefaultCloudFoundryOperations cfOperations;

    private GetLogic getLogic;

    private DiffLogic diffLogic;

    /**
     * Creates a new instance that will use the provided cf operations internally.
     *
     * @param cfOperations the cf operations that should be used to communicate with
     *                     the cf instance
     * @throws NullPointerException if the argument is null
     */
    public ApplyLogic(@Nonnull DefaultCloudFoundryOperations cfOperations) {
        checkNotNull(cfOperations);

        this.cfOperations = cfOperations;
        this.getLogic = new GetLogic();
        this.diffLogic = new DiffLogic();
    }

    /**
     * Assign users as space developers that are not present in the live system and
     * revoke space developers permission, if its present in the live system but not
     * defined in <code>desiredSpaceDevelopers</code>. In case of any non-recoverable error, the
     * procedure is discontinued.
     *
     * @param desiredSpaceDevelopers the space developers that should all be present
     *                               in the live system after the procedure.
     * @throws NullPointerException if the argument is null.
     */
    public void applySpaceDevelopers(@Nonnull List<String> desiredSpaceDevelopers) {
        checkNotNull(desiredSpaceDevelopers);

        SpaceDevelopersOperations spaceDevelopersOperations = new SpaceDevelopersOperations(cfOperations);
        log.info("Fetching information about space developers...");
        GetLogic getLogic = new GetLogic();
        List<String> liveSpaceDevelopers = getLogic.getSpaceDevelopers(spaceDevelopersOperations);
        log.info("Information fetched.");

        ConfigBean desiredSpaceDevelopersConfig = createConfigFromSpaceDevelopers(desiredSpaceDevelopers);
        ConfigBean liveSpaceDevelopersConfig = createConfigFromSpaceDevelopers(liveSpaceDevelopers);

        // compare entire configs as the diff wrapper is only suited for diff trees of these
        DiffLogic diffLogic = new DiffLogic();
        log.info("Comparing the space developers...");
        DiffResult wrappedDiff = diffLogic.createDiffResult(liveSpaceDevelopersConfig, desiredSpaceDevelopersConfig);
        log.info("Space developers compared.");

        CfContainerChange spaceDevelopersChange = wrappedDiff.getSpaceDevelopersChange();
        if (spaceDevelopersChange == null) {
            log.info("There is nothing to apply");
        } else {
            Flux<Void> spaceDevelopersRequests = Flux.just(spaceDevelopersChange)
                    .flatMap(spaceDeveloperChange -> SpaceDevelopersRequestsPlaner
                            .createSpaceDevelopersRequests(spaceDevelopersOperations, spaceDeveloperChange))
                    .onErrorContinue(log::warning);
            log.info("Applying changes to space developers...");
            spaceDevelopersRequests.blockLast();
        }
    }

    // TODO update the documentation as soon as the method does more than just
    // creating applications

    /**
     * Creates all provided applications that are not present in the live system. In
     * case of any error, the procedure is discontinued.
     *
     * @param desiredApplications the applications that should all be present in the
     *                            live system after the procedure
     * @throws ApplyException       if an error occurs during the procedure
     * Creates all provided applications that are not present in the live system.
     * @param desiredApplications the applications that should all be present in the live system after the procedure
     * @throws ApplyException if an error occurs during the procedure
     * @throws NullPointerException if the argument is null
     */
    public void applyApplications(@Nonnull Map<String, ApplicationBean> desiredApplications) {
        checkNotNull(desiredApplications);

        ApplicationsOperations applicationsOperations = new ApplicationsOperations(cfOperations);
        log.info("Fetching information about applications...");
        Map<String, ApplicationBean> liveApplications = this.getLogic.getApplications(applicationsOperations);
        log.info("Information fetched.");

        // that way only the applications of the live system are compared in the diff
        ConfigBean desiredApplicationsConfig = createConfigFromApplications(desiredApplications);
        ConfigBean liveApplicationsConfig = createConfigFromApplications(liveApplications);

        // compare entire configs as the diff wrapper is only suited for diff trees of these
        log.verbose("Comparing the applications...");
        DiffResult diffResult = this.diffLogic.createDiffResult(liveApplicationsConfig, desiredApplicationsConfig);
        log.verbose("Applications compared.");

        Map<String, List<CfChange>> allApplicationChanges = diffResult.getApplicationChanges();

        Flux<Void> applicationRequests = Flux.fromIterable(allApplicationChanges.entrySet())
                .flatMap( appChangeEntry -> ApplicationRequestsPlaner.createApplyRequests(applicationsOperations,
                        appChangeEntry.getKey(),
                        appChangeEntry.getValue()))
                .onErrorContinue(log::warning);
        applicationRequests.blockLast();

        log.info("Applying changes to applications...");
    }

    //TODO update the documentation as soon as the method does more than just creating/removing services
    /**
     * Creates/removes all desired services in your live cf instance.
     * @param desiredServices the services that should all be present in the live system after the procedure
     * @throws ApplyException if an error occurs during the procedure
     * @throws NullPointerException if the argument is null
     */
    public void applyServices(@Nonnull Map<String, ServiceBean> desiredServices) {
        checkNotNull(desiredServices);

        ServicesOperations servicesOperations = new ServicesOperations(cfOperations);
        log.info("Fetching information about services...");
        Map<String, ServiceBean> liveServices = this.getLogic.getServices(servicesOperations);
        log.info("Information fetched.");

        // that way only the applications of the live system are compared in the diff
        ConfigBean desiredServicesConfig = createConfigFromServices(desiredServices);
        ConfigBean liveServicesConfig = createConfigFromServices(liveServices);

        // compare entire configs as the diff wrapper is only suited for diff trees of these
        log.verbose("Comparing the services...");
        DiffResult diffResult = this.diffLogic.createDiffResult(liveServicesConfig, desiredServicesConfig);
        log.verbose("Services compared.");

        Map<String, List<CfChange>> allServicesChanges = diffResult.getServiceChanges();

        Flux<Void> serviceRequests = Flux.fromIterable(allServicesChanges.entrySet())
                .flatMap( serviceChangeEntry ->
                        ServiceRequestsPlaner.createApplyRequests(servicesOperations,
                                serviceChangeEntry.getKey(),
                                serviceChangeEntry.getValue()))
                .onErrorContinue(log::warning);
        serviceRequests.blockLast();

        log.info("Applying changes to services...");
    }


    /**
     * @param spaceDevelopers the space developer that should be
     *                             contained in the resulting config bean.
     * @return a config bean only containing the entered space developers.
     */
    private ConfigBean createConfigFromSpaceDevelopers(List<String> spaceDevelopers) {
        SpecBean specBean = new SpecBean();
        specBean.setSpaceDevelopers(spaceDevelopers);

        ConfigBean configBean = new ConfigBean();
        configBean.setSpec(specBean);

        return configBean;
    }

    /**
     * @param applicationBeans the application beans that should be contained in the
     *                         resulting config bean
     * @return a config bean only containing the entered application beans
     */
    private ConfigBean createConfigFromApplications(Map<String, ApplicationBean> applicationBeans) {
        SpecBean applicationsSpecBean = new SpecBean();
        applicationsSpecBean.setApps(applicationBeans);
        ConfigBean applicationsConfigBean = new ConfigBean();
        applicationsConfigBean.setSpec(applicationsSpecBean);
        return applicationsConfigBean;
    }

    /**
     * @param serviceBeans the service beans that should be contained in the resulting config bean
     * @return a config bean only containing the entered service beans
     */
    private ConfigBean createConfigFromServices(Map<String, ServiceBean> serviceBeans) {
        SpecBean servicesSpecBean = new SpecBean();
        servicesSpecBean.setServices(serviceBeans);
        ConfigBean servicesConfigBean = new ConfigBean();
        servicesConfigBean.setSpec(servicesSpecBean);
        return servicesConfigBean;
    }

    public void setDiffLogic(DiffLogic diffLogic) {
        this.diffLogic = diffLogic;
    }

    public void setGetLogic(GetLogic getLogic) {
        this.getLogic = getLogic;
    }
}
