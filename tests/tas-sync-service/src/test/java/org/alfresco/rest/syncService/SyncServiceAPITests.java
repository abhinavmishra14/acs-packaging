package org.alfresco.rest.syncService;

import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import org.alfresco.rest.SyncServiceTest;
import org.alfresco.rest.model.RestSubscriberModel;
import org.alfresco.rest.model.RestSubscriberModelCollection;
import org.alfresco.rest.model.RestSyncNodeSubscriptionModel;
import org.alfresco.rest.model.RestSyncNodeSubscriptionModelCollection;
import org.alfresco.rest.model.RestSyncServiceHealthCheckModel;
import org.alfresco.rest.model.RestSyncSetChangesModel;
import org.alfresco.rest.model.RestSyncSetGetModel;
import org.alfresco.rest.model.RestSyncSetRequestModel;
import org.alfresco.rest.requests.syncServiceAPI.Healthcheck;
import org.alfresco.rest.requests.syncServiceAPI.Subscriptions.TYPE;
import org.alfresco.utility.LogFactory;
import org.alfresco.utility.model.FolderModel;
import org.alfresco.utility.model.SiteModel;
import org.alfresco.utility.model.TestGroup;
import org.alfresco.utility.model.UserModel;

/**
 * Sanity tests for Testing Sync Service APIs
 *
 * @author mbhave
 */
@Test(groups = {TestGroup.SYNC_API, TestGroup.REQUIRES_AMP})
public class SyncServiceAPITests extends SyncServiceTest
{
    private static Logger LOG = LogFactory.getLogger();

    private UserModel adminUserModel;
    private SiteModel siteModel;
    private FolderModel targetFolder1;
    private FolderModel targetFolder2;

    private RestSyncNodeSubscriptionModelCollection nodeSubscriptions;

    private String clientVersion;

    @BeforeClass(alwaysRun = true)
    public void dataPreparation() throws Exception
    {
        adminUserModel = dataUser.getAdminUser();
        restClientAlfresco.authenticateUser(adminUserModel);

        siteModel = dataSite.usingUser(adminUserModel).createPublicRandomSite();

        targetFolder1 = dataContent.usingUser(adminUserModel).usingSite(siteModel).createFolder();

        targetFolder2 = dataContent.usingUser(adminUserModel).usingSite(siteModel).createFolder();

        siteModel.setGuid(restClientAlfresco.authenticateUser(adminUserModel).withCoreAPI().usingSite(siteModel).getSite().getGuidWithoutVersion());

        clientVersion = "1.1";
    }

    @Test(priority = 1)
    public void testSyncServiceHealthCheck() throws Exception
    {
        // Perform HealthCheck
        Healthcheck healthCheck = restClientAlfresco.authenticateUser(adminUserModel).withPrivateAPI().doHealthCheck();

        RestSyncServiceHealthCheckModel syncServiceHealthCheck = healthCheck.getHealthcheck();

        Assert.assertTrue(syncServiceHealthCheck.getActiveMQConnection().getHealthy());
        Assert.assertTrue(syncServiceHealthCheck.getDatabaseConnection().getHealthy());
        Assert.assertTrue(syncServiceHealthCheck.getRepositoryConnection().getHealthy());
        Assert.assertTrue(syncServiceHealthCheck.getDeadlocks().getHealthy());
        Assert.assertTrue(syncServiceHealthCheck.getEventsHealthCheck().getHealthy());
        Assert.assertTrue(syncServiceHealthCheck.getSyncServiceIdCheck().getHealthy());
        Assert.assertTrue(syncServiceHealthCheck.getVersionCheck().getHealthy());
    }

    @Test(priority = 2)
    public void testNewDeviceSubscription() throws Exception
    {
        // Register Device
        RestSubscriberModel deviceSubscription = restClientAlfresco.authenticateUser(adminUserModel).withPrivateAPI().withSubscribers()
                .registerDevice("windows", clientVersion);
        restClientAlfresco.assertStatusCodeIs(HttpStatus.CREATED);

        restClientAlfresco.onResponse().assertThat().body("entry.id", org.hamcrest.Matchers.notNullValue());
        restClientAlfresco.onResponse().assertThat().body("entry.id", org.hamcrest.Matchers.equalTo(deviceSubscription.getId()));

        // Get Registered Devices
        RestSubscriberModelCollection deviceSubscriptions = restClientAlfresco.authenticateUser(adminUserModel).withParams("maxItems=500").withPrivateAPI()
                .withSubscribers().getSubscribers();

        restClientAlfresco.onResponse().assertThat().body("list.entries.entry", org.hamcrest.Matchers.notNullValue());
        int noOfSubscriptions = deviceSubscriptions.getEntries().size();
        Assert.assertTrue(noOfSubscriptions > 0, "No Device subscriptions found when expected");
        Assert.assertTrue(deviceSubscriptions.getEntries().get(noOfSubscriptions - 1).onModel().getId().equals(deviceSubscription.getId()), "Not found: "
                + deviceSubscription.getId());

    }

    @Test(priority = 3)
    public void testDeviceSubcriptionToNode() throws Exception
    {
        // Register Device
        RestSubscriberModel deviceSubscription = restClientAlfresco.authenticateUser(adminUserModel).withPrivateAPI().withSubscribers()
                .registerDevice("windows", clientVersion);

        // Subscribe to a node
        RestSyncNodeSubscriptionModel nodeSubscription = restClientAlfresco.withPrivateAPI().withSubscriber(deviceSubscription)
                .subscribeToNode(targetFolder1.getNodeRefWithoutVersion(), TYPE.BOTH);
        restClientAlfresco.assertStatusCodeIs(HttpStatus.CREATED);

        restClientAlfresco.onResponse().assertThat().body("entry.deviceSubscriptionId", org.hamcrest.Matchers.notNullValue());
        restClientAlfresco.onResponse().assertThat().body("entry.deviceSubscriptionId", org.hamcrest.Matchers.equalTo(deviceSubscription.getId()));

        restClientAlfresco.onResponse().assertThat().body("entry.targetNodeId", org.hamcrest.Matchers.notNullValue());
        restClientAlfresco.onResponse().assertThat().body("entry.targetNodeId", org.hamcrest.Matchers.equalTo(targetFolder1.getNodeRefWithoutVersion()));

        restClientAlfresco.onResponse().assertThat().body("entry.id", org.hamcrest.Matchers.notNullValue());
        restClientAlfresco.onResponse().assertThat().body("entry.id", org.hamcrest.Matchers.equalTo(nodeSubscription.getId()));

        // Get Sync Node Subscriptions
        nodeSubscriptions = restClientAlfresco.withPrivateAPI().withSubscriber(deviceSubscription).getSubscriptions();

        nodeSubscriptions.assertThat().entriesListContains("id", nodeSubscription.getId());

        restClientAlfresco.onResponse().assertThat().body("list.entries.entry", org.hamcrest.Matchers.notNullValue());

        int noOfSubscriptions = nodeSubscriptions.getEntries().size();
        Assert.assertTrue(noOfSubscriptions > 0, "No Sync subscriptions found when expected");
        LOG.info("Subscriptions: " + nodeSubscriptions);
        Assert.assertEquals(nodeSubscriptions.getEntries().get(noOfSubscriptions - 1).onModel().getId(), nodeSubscription.getId());
        Assert.assertEquals(nodeSubscriptions.getEntries().get(noOfSubscriptions - 1).onModel().getDeviceSubscriptionId(),
                nodeSubscription.getDeviceSubscriptionId());

        // Get Sync Node Subscription by subscriptionId
        RestSyncNodeSubscriptionModel subscription = restClientAlfresco.withPrivateAPI().withSubscriber(deviceSubscription).getSubscription(nodeSubscription.getId());

        subscription.assertThat().field("id").isNotNull().assertThat().field("id").is(nodeSubscription.getId()).assertThat().field("deviceSubscriptionId")
                .isNotNull().assertThat().field("deviceSubscriptionId").is(nodeSubscription.getDeviceSubscriptionId());
    }

    @Test(priority = 4)
    public void testDeviceSubcriptionToMultipleNodes() throws Exception
    {
        // Register Device
        RestSubscriberModel deviceSubscription = restClientAlfresco.authenticateUser(adminUserModel).withPrivateAPI().withSubscribers()
                .registerDevice("windows", "1.2");

        // Subscribe to a node
        restClientAlfresco.withPrivateAPI().withSubscriber(deviceSubscription)
                .subscribeToNodes(siteModel.getGuid(), targetFolder1.getNodeRefWithoutVersion(), targetFolder2.getNodeRefWithoutVersion());
        restClientAlfresco.assertStatusCodeIs(HttpStatus.CREATED);

        // Get Sync Node Subscriptions
        RestSyncNodeSubscriptionModelCollection subscriptionList = restClientAlfresco.withPrivateAPI().withSubscriber(deviceSubscription).getSubscriptions();

        int countOfEntries = subscriptionList.getEntries().size();
        Assert.assertTrue(countOfEntries == 3, "Node subscriptions NOT found when expected");
    }

    @Test(priority = 5)
    public void testSyncProcess() throws Exception
    {
        // Register Device
        List<RestSyncSetChangesModel> clientChanges = new LinkedList<>();
        RestSubscriberModel deviceSubscription = restClientAlfresco.authenticateUser(adminUserModel).withPrivateAPI().withSubscribers()
                .registerDevice("windows", "1.2");

        // Subscribe to a node
        RestSyncNodeSubscriptionModel nodeSubscription = restClientAlfresco.withPrivateAPI().withSubscriber(deviceSubscription)
                .subscribeToNode(targetFolder1.getNodeRefWithoutVersion(), TYPE.BOTH);
        restClientAlfresco.assertStatusCodeIs(HttpStatus.CREATED);

        // Start Sync
        RestSyncSetRequestModel syncRequest = restClientAlfresco.authenticateUser(adminUserModel).withPrivateAPI().withSubscription(nodeSubscription)
                .startSync(nodeSubscription, clientChanges, clientVersion);

        restClientAlfresco.assertStatusCodeIs(HttpStatus.CREATED);

        Assert.assertNotNull(syncRequest.getSyncId(), "Sync Id not expected to be null");
        String syncId = syncRequest.getSyncId();
        Assert.assertNotNull(syncRequest.getUrl(), "Sync URL not expected to be null");
        Assert.assertTrue(syncRequest.getUrl().endsWith(syncId), "Incorrect Sync URL, expected to end with syncId: " + syncId);
        Assert.assertEquals(syncRequest.getStatus(), "ok", "Sync Status not ok.  Actual: " + syncRequest.getStatus());
        Assert.assertNull(syncRequest.getMessage(), "Sync Message was expected to be null. Actual: " + syncRequest.getMessage());
        Assert.assertNull(syncRequest.getError(), "Sync Error expected to be null. Actual: " + syncRequest.getError());

        // Get Sync Changes
        RestSyncSetGetModel changeSet = restClientAlfresco.withPrivateAPI().withSubscription(nodeSubscription).getSync(nodeSubscription, syncRequest);

        restClientAlfresco.assertStatusCodeIs(HttpStatus.OK);
        Assert.assertNotNull(changeSet.getSyncId(), "Sync Id not expected to be null");
        Assert.assertTrue(changeSet.getSyncId().equals(syncId), "Incorrect SyncId in the response");
        Assert.assertNotNull(changeSet.getMoreChanges(), "MoreChanges expected to be true or false. It's null");

        // End Sync
        restClientAlfresco.withPrivateAPI().withSubscription(nodeSubscription).endSync(nodeSubscription, syncRequest);
        restClientAlfresco.assertStatusCodeIs(HttpStatus.NO_CONTENT);
    }
}
