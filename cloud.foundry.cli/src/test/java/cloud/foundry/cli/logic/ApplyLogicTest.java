package cloud.foundry.cli.logic;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationBean;
import cloud.foundry.cli.crosscutting.mapping.beans.ApplicationManifestBean;

import org.cloudfoundry.client.v2.spaces.AssociateSpaceDeveloperByUsernameRequest;
import org.cloudfoundry.client.v2.spaces.RemoveSpaceDeveloperByUsernameRequest;
import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.operations.DefaultCloudFoundryOperations;
import org.cloudfoundry.operations.applications.Applications;
import org.cloudfoundry.operations.applications.DeleteApplicationRequest;
import org.cloudfoundry.operations.applications.GetApplicationManifestRequest;
import org.cloudfoundry.operations.applications.GetApplicationRequest;
import org.cloudfoundry.operations.applications.PushApplicationManifestRequest;
import org.cloudfoundry.client.v2.spaces.Spaces;
import org.cloudfoundry.operations.applications.ApplicationManifest;
import org.cloudfoundry.operations.applications.ApplicationSummary;
import org.cloudfoundry.operations.useradmin.ListSpaceUsersRequest;
import org.cloudfoundry.operations.useradmin.SpaceUsers;
import org.cloudfoundry.operations.useradmin.UserAdmin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Test for {@link ApplyLogic}
 */
public class ApplyLogicTest {

    @Test
    public void testConstructorWithNull() {
        assertThrows(NullPointerException.class, () -> new ApplyLogic(null));
    }

    @Test
    public void testApplyApplicationsWithNull() {
        ApplyLogic applyLogic = new ApplyLogic(mock(DefaultCloudFoundryOperations.class));

        assertThrows(NullPointerException.class, () -> applyLogic.applyApplications(null));
    }

    @Test
    public void testApplySpaceDevelopersWithNull() {
        ApplyLogic applyLogic = new ApplyLogic(mock(DefaultCloudFoundryOperations.class));

        assertThrows(NullPointerException.class, () -> applyLogic.applySpaceDevelopers(null));
    }

    @Test
    public void testApplySpaceDevelopersAssignAndRemoveSpaceDevelopers() {
        // given
        // listSpacedevs to apply
        List<String> spaceDevelopersToApply = new LinkedList<>();
        spaceDevelopersToApply.add("Mr. Bean");
        spaceDevelopersToApply.add("toAdd");

        // list live
        List<String> spaceDevelopersLive = new LinkedList<>();
        spaceDevelopersLive.add("Mr. Bean");
        spaceDevelopersLive.add("toDelete");

        DefaultCloudFoundryOperations cfOperationsMock = mockSpaceDevelopersGetAll(spaceDevelopersLive);
        CloudFoundryClient cloudFoundryClientMock = mock(CloudFoundryClient.class);
        when(cfOperationsMock.getCloudFoundryClient()).thenReturn(cloudFoundryClientMock);
        Spaces spacesMock = mock(Spaces.class);
        when(cloudFoundryClientMock.spaces()).thenReturn(spacesMock);

        // assign
        AtomicReference<AssociateSpaceDeveloperByUsernameRequest> assignmentRequest = assignSpaceDevelopersMock(
                spacesMock);

        // delete
        AtomicReference<RemoveSpaceDeveloperByUsernameRequest> removalRequest = deleteSpaceDevelopersMock(spacesMock);

        ApplyLogic applyLogic = new ApplyLogic(cfOperationsMock);

        // when
        applyLogic.applySpaceDevelopers(spaceDevelopersToApply);

        // then
        verify(spacesMock, times(1)).removeDeveloperByUsername(any(RemoveSpaceDeveloperByUsernameRequest.class));
        verify(spacesMock, times(1)).associateDeveloperByUsername(any(AssociateSpaceDeveloperByUsernameRequest.class));

        AssociateSpaceDeveloperByUsernameRequest assignrequest = assignmentRequest.get();
        assertThat(assignrequest, notNullValue());
        assertThat(assignrequest.getUsername(), is("toAdd"));

        RemoveSpaceDeveloperByUsernameRequest deleterequest = removalRequest.get();
        assertThat(deleterequest, notNullValue());
        assertThat(deleterequest.getUsername(), is("toDelete"));

    }

    private DefaultCloudFoundryOperations mockSpaceDevelopersGetAll(List<String> spaceDevelopersLive) {
        DefaultCloudFoundryOperations cfOperationsMock = mock(DefaultCloudFoundryOperations.class);

        when(cfOperationsMock.getSpace()).thenReturn("spaceName");
        when(cfOperationsMock.getOrganization()).thenReturn("organizationName");
        Mono<String> monoMock = mock(Mono.class);
        when(cfOperationsMock.getSpaceId()).thenReturn(monoMock);
        when(monoMock.block()).thenReturn("spaceID");

        UserAdmin userAdminMock = mock(UserAdmin.class);
        when(cfOperationsMock.userAdmin()).thenReturn(userAdminMock);

        AtomicReference<ListSpaceUsersRequest> listingRequest = new AtomicReference<>(null);
        when(userAdminMock.listSpaceUsers(any(ListSpaceUsersRequest.class)))
                .then(invocation -> {
                    listingRequest.set(invocation.getArgument(0));
                    return Mono.just(spaceDevelopersLive)
                            .map(list -> SpaceUsers.builder().addAllDevelopers(list).build());
                });

        return cfOperationsMock;
    }

    // mock for assign space devs
    private AtomicReference<AssociateSpaceDeveloperByUsernameRequest> assignSpaceDevelopersMock(Spaces spacesMock) {
        Mono<Void> assignMonoMock = mock(Mono.class);
        AtomicReference<AssociateSpaceDeveloperByUsernameRequest> assignmentRequest = new AtomicReference<>(null);
        when(spacesMock.associateDeveloperByUsername(any(AssociateSpaceDeveloperByUsernameRequest.class)))
                .then(invocation -> {
                    assignmentRequest.set(invocation.getArgument(0));
                    return Mono.empty();
                });

        when(assignMonoMock.onErrorContinue(any(Predicate.class), any())).thenReturn(assignMonoMock);
        when(assignMonoMock.block()).thenReturn(null);

        return assignmentRequest;
    }

    // mock for delete space devs
    private AtomicReference<RemoveSpaceDeveloperByUsernameRequest> deleteSpaceDevelopersMock(Spaces spacesMock) {
        Mono<Void> deleteMonoMock = mock(Mono.class);
        AtomicReference<RemoveSpaceDeveloperByUsernameRequest> removalRequest = new AtomicReference<>(null);
        when(spacesMock.removeDeveloperByUsername(any(RemoveSpaceDeveloperByUsernameRequest.class)))
                .then(invocation -> {
                    removalRequest.set(invocation.getArgument(0));
                    return Mono.empty();
                });

        when(deleteMonoMock.onErrorContinue(any(Predicate.class), any())).thenReturn(deleteMonoMock);
        when(deleteMonoMock.block()).thenReturn(null);

        return removalRequest;
    }

    @Test
    public void testApplyApplicationsCreatesApplication() {
        // given
        DefaultCloudFoundryOperations cfOperationsMock = mock(DefaultCloudFoundryOperations.class);
        Applications applicationsMock = mock(Applications.class);

        // from now on: mock-setup for ApplicationOperations.getAll delivers empty
        // applications
        when(cfOperationsMock.applications()).thenReturn(applicationsMock);
        when(applicationsMock.list()).thenReturn(Flux.empty());

        // from now on: mock-setup for ApplicationOperations.create delivers successful
        // creation
        when(applicationsMock.get(any(GetApplicationRequest.class))).thenThrow(IllegalArgumentException.class);
        Mono<Void> pushManifestMonoMock = mock(Mono.class);

        // this will contain the received PushApplicationManifestRequest when
        // pushManifest is called
        AtomicReference<PushApplicationManifestRequest> receivedPushRequest = new AtomicReference<>(null);

        when(applicationsMock.pushManifest(any(PushApplicationManifestRequest.class)))
            .thenAnswer((Answer<Mono<Void>>) invocation -> {
                receivedPushRequest.set(invocation.getArgument(0));
                return pushManifestMonoMock;
            });
        when(pushManifestMonoMock.onErrorContinue(any(Predicate.class), any())).thenReturn(pushManifestMonoMock);
        when(pushManifestMonoMock.block()).thenReturn(null);

        // from now on: setup application to apply
        Map<String, ApplicationBean> applicationsToApply = createDesiredApplications("someApplicationName",
            "/some/path", "someBuildpack");

        ApplyLogic applyLogic = new ApplyLogic(cfOperationsMock);

        // when
        applyLogic.applyApplications(applicationsToApply);

        // then
        verify(applicationsMock).list();

        PushApplicationManifestRequest actualReceivedPushRequest = receivedPushRequest.get();
        assertThat(actualReceivedPushRequest, is(notNullValue()));
        assertThat(actualReceivedPushRequest.getManifests().size(), is(1));

        ApplicationManifest manifest = actualReceivedPushRequest.getManifests().get(0);
        assertThat(manifest.getName(), is("someApplicationName"));
        assertThat(manifest.getPath(), is(Paths.get("/some/path")));
        assertThat(manifest.getBuildpack(), is("someBuildpack"));
    }


    @Test
    public void testApplyApplicationsWithoutDifference() {
        // given
        Map<String, ApplicationBean> appsToApply = createDesiredApplications("app1", "path", "someBuildpack");

        // mock-setup for ApplicationOperations.getAll() delivers 1 application
        ApplicationManifest appManifest1 = createMockApplicationManifest("app1", "path", "someBuildpack");
        ApplicationSummary summary1 = createMockApplicationSummary(appManifest1);

        Applications applicationsMock = mock(Applications.class);
        DefaultCloudFoundryOperations cfOperationsMock = createMockCloudFoundryOperations(
                                                            Arrays.asList(summary1),
                                                            Arrays.asList(appManifest1),
                                                            applicationsMock);
        ApplyLogic applyLogic = new ApplyLogic(cfOperationsMock);
        //when
        applyLogic.applyApplications(appsToApply);

        // then
        verify(applicationsMock).list();
        verify(applicationsMock, times(0)).delete(any(DeleteApplicationRequest.class));
        verify(applicationsMock, times(0)).pushManifest(any(PushApplicationManifestRequest.class));
    }
    
    @Test
    public void testApplyApplicationsRemovesApplication() {
        // given
        Map<String, ApplicationBean> appsToApply = createDesiredApplications("app1", "/some/path", "someBuildpack");

        // mock-setup for ApplicationOperations.getAll() delivers 3 applications (app1, app2, app3)
        ApplicationManifest appManifest1 = createMockApplicationManifest("app1", "/some/path", "someBuildpack");
        ApplicationSummary summary1 = createMockApplicationSummary(appManifest1);

        ApplicationManifest appManifest2 = createMockApplicationManifest("app2", "/some/path", "someBuildpack");
        ApplicationSummary summary2 = createMockApplicationSummary(appManifest2);

        ApplicationManifest appManifest3 = createMockApplicationManifest("app3", "/some/path", "someBuildpack");
        ApplicationSummary summary3 = createMockApplicationSummary(appManifest3);

        Applications applicationsMock = mock(Applications.class);
        DefaultCloudFoundryOperations cfOperationsMock = createMockCloudFoundryOperations(
                                                            Arrays.asList(summary1, summary2, summary3),
                                                            Arrays.asList(appManifest1, appManifest2, appManifest3),
                                                            applicationsMock);

        Void voidMock = mock(Void.class);
        Mono<Void> deletedMonoMock = Mono.just(voidMock);
        
        // This contains all the DeleteApplicationRequests received when delete is called
        CopyOnWriteArrayList<DeleteApplicationRequest> receivedDeleteRequests = new
                                                           CopyOnWriteArrayList<DeleteApplicationRequest>();

        when(applicationsMock.delete(any(DeleteApplicationRequest.class)))
            .thenAnswer((Answer<Mono<Void>>) invocation -> {
                receivedDeleteRequests.add(invocation.getArgument(0));

                return deletedMonoMock;
            });

        ApplyLogic applyLogic = new ApplyLogic(cfOperationsMock);

        // when
        applyLogic.applyApplications(appsToApply);

        // then
        verify(applicationsMock).list();

        ListIterator<DeleteApplicationRequest> listDeleteRequests = receivedDeleteRequests.listIterator();
        assertThat(receivedDeleteRequests.size(), is(2));

        DeleteApplicationRequest deleteRequest1 = listDeleteRequests.next();
        assertThat(deleteRequest1, is(notNullValue()));
        assertThat(deleteRequest1.getName(), is("app2"));

        DeleteApplicationRequest deleteRequest2 = listDeleteRequests.next();
        assertThat(deleteRequest2, is(notNullValue()));
        assertThat(deleteRequest2.getName(), is("app3"));
    }

    private Map<String, ApplicationBean> createDesiredApplications(String appname, String path, String buildpack) {
        Map<String, ApplicationBean> appconfig = new HashMap<>();
        ApplicationBean applicationBean = new ApplicationBean();
        applicationBean.setPath(path);
        ApplicationManifestBean manifestBean = new ApplicationManifestBean();
        applicationBean.setManifest(manifestBean);
        manifestBean.setBuildpack(buildpack);
        manifestBean.setInstances(1);
        manifestBean.setMemory(Integer.MAX_VALUE);
        appconfig.put(appname, applicationBean);

        return appconfig;
    }

    private DefaultCloudFoundryOperations createMockCloudFoundryOperations(List<ApplicationSummary> appSummaries,
        List<ApplicationManifest> manifests, Applications applicationsMock) {

        DefaultCloudFoundryOperations cfMock = Mockito.mock(DefaultCloudFoundryOperations.class);
        Flux<ApplicationSummary> flux = Flux.fromIterable(appSummaries);

        when(cfMock.applications()).thenReturn(applicationsMock);
        when(applicationsMock.getApplicationManifest(any(GetApplicationManifestRequest.class)))
            .thenAnswer((Answer<Mono<ApplicationManifest>>) invocation -> {
                GetApplicationManifestRequest request = invocation.getArgument(0);

                for (ApplicationManifest manifest : manifests) {
                    if (manifest.getName().equals(request.getName())) {
                        return Mono.just(manifest);
                    }
                }
                throw new RuntimeException("RuntimeException");
            });
        when(applicationsMock.list()).thenReturn(flux);

        return cfMock;
    }

    private ApplicationManifest createMockApplicationManifest(String appName, String path, String buildpack) {

        return ApplicationManifest.builder()
            .name(appName)
            .buildpack(buildpack)
            .path(Paths.get(path))
            .instances(1)
            .memory(Integer.MAX_VALUE)
            .build();
    }

    private ApplicationSummary createMockApplicationSummary(ApplicationManifest manifest) {
        
        return ApplicationSummary.builder()
            .name(manifest.getName())
            .diskQuota(100)
            .id("summary_id")
            .instances(manifest.getInstances())
            .memoryLimit(manifest.getMemory())
            .requestedState("SOMESTATE")
            .runningInstances(1)
            .build();
    }

}
