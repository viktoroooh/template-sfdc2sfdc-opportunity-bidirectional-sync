/**
 * Mule Anypoint Template
 * Copyright (c) MuleSoft, Inc.
 * All rights reserved.  http://www.mulesoft.com
 */

package org.mule.templates.integration;

import static org.mule.templates.builders.SfdcObjectBuilder.anOpportunity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleException;
import org.mule.api.lifecycle.InitialisationException;
import org.mule.processor.chain.InterceptingChainLifecycleWrapper;
import org.mule.processor.chain.SubflowInterceptingChainLifecycleWrapper;
import org.mule.tck.junit4.rule.DynamicPort;
import org.mule.templates.AbstractTemplatesTestCase;

import com.mulesoft.module.batch.BatchTestHelper;
import com.sforce.soap.partner.SaveResult;

/**
 * The objective of this class is validating the correct behavior of the flows
 * for this Mule Anypoint Template
 * 
 */
@SuppressWarnings("unchecked")
public class BusinessLogicTestDoNotCreateAccountIT extends AbstractTemplatesTestCase {

	private static final String A_INBOUND_FLOW_NAME = "triggerSyncFromAFlow";
	private static final String B_INBOUND_FLOW_NAME = "triggerSyncFromBFlow";
	private static final String ANYPOINT_TEMPLATE_NAME = "sfdc2sfdc-bidirectional-opportunity-sync";
	private static final int TIMEOUT_MILLIS = 60;

	private static List<String> opportunitiesCreatedInA = new ArrayList<String>();
	private static List<String> opportunitiesCreatedInB = new ArrayList<String>();
	private static SubflowInterceptingChainLifecycleWrapper deleteOpportunityFromAFlow;
	private static SubflowInterceptingChainLifecycleWrapper deleteOpportunityFromBFlow;

	private SubflowInterceptingChainLifecycleWrapper createOpportunityInAFlow;
	private SubflowInterceptingChainLifecycleWrapper createOpportunityInBFlow;
	private InterceptingChainLifecycleWrapper queryOpportunityFromAFlow;
	private InterceptingChainLifecycleWrapper queryOpportunityFromBFlow;
	private BatchTestHelper batchTestHelper;
	
	@Rule
	public DynamicPort port = new DynamicPort("http.port");

	@BeforeClass
	public static void beforeTestClass() {
		System.setProperty("page.size", "1000");

		// Set polling frequency to 10 seconds
		System.setProperty("poll.frequencyMillis", "10000");

		// Set default water-mark expression to current time
		System.clearProperty("watermark.default.expression");
		DateTime now = new DateTime(DateTimeZone.UTC).minusMinutes(1);
		DateTimeFormatter dateFormat = DateTimeFormat
				.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
		System.setProperty("watermark.default.expression",
				now.toString(dateFormat));

		System.setProperty("trigger.policy", "poll");
		System.setProperty("account.sync.policy", "");
	}
	
	@Before
	public void setUp() throws MuleException {
		stopAutomaticPollTriggering();
		getAndInitializeFlows();
		
		batchTestHelper = new BatchTestHelper(muleContext);
	}

	@AfterClass
	public static void shutDown() throws MuleException, Exception {
		System.clearProperty("poll.frequencyMillis");
		System.clearProperty("watermark.default.expression");
		System.clearProperty("account.sync.policy");
		System.clearProperty("trigger.policy");
	}

	@After
	public void tearDown() throws MuleException, Exception {
		cleanUpSandboxesByRemovingTestOpportunities();
	}

	private void stopAutomaticPollTriggering() throws MuleException {
		stopFlowSchedulers(A_INBOUND_FLOW_NAME);
		stopFlowSchedulers(B_INBOUND_FLOW_NAME);
	}

	private void getAndInitializeFlows() throws InitialisationException {
		// Flow for creating opportunities in sfdc A instance
		createOpportunityInAFlow = getSubFlow("createOpportunityInAFlow");
		createOpportunityInAFlow.initialise();

		// Flow for creating opportunities in sfdc B instance
		createOpportunityInBFlow = getSubFlow("createOpportunityInBFlow");
		createOpportunityInBFlow.initialise();

		// Flow for deleting opportunities in sfdc A instance
		deleteOpportunityFromAFlow = getSubFlow("deleteOpportunityFromAFlow");
		deleteOpportunityFromAFlow.initialise();

		// Flow for deleting opportunities in sfdc B instance
		deleteOpportunityFromBFlow = getSubFlow("deleteOpportunityFromBFlow");
		deleteOpportunityFromBFlow.initialise();

		// Flow for querying opportunities in sfdc A instance
		queryOpportunityFromAFlow = getSubFlow("queryOpportunityFromAFlow");
		queryOpportunityFromAFlow.initialise();

		// Flow for querying opportunities in sfdc B instance
		queryOpportunityFromBFlow = getSubFlow("queryOpportunityFromBFlow");
		queryOpportunityFromBFlow.initialise();
	}

	private static void cleanUpSandboxesByRemovingTestOpportunities() throws MuleException, Exception {
		final List<String> idList = new ArrayList<String>();
		for (String opportunity : opportunitiesCreatedInA) {
			idList.add(opportunity);
		}
		deleteOpportunityFromAFlow.process(getTestEvent(idList,	MessageExchangePattern.REQUEST_RESPONSE));
		
		idList.clear();
		
		for (String opportunity : opportunitiesCreatedInB) {
			idList.add(opportunity);
		}
		deleteOpportunityFromBFlow.process(getTestEvent(idList,	MessageExchangePattern.REQUEST_RESPONSE));
	}

	@Test
	public void testFromBtoAWithoutAccount()
			throws MuleException, Exception {
		// Build test opportunity
		Map<String, Object> opportunity = anOpportunity()
				.with("Amount", "123456")
				.with("Name", ANYPOINT_TEMPLATE_NAME + "-" + System.currentTimeMillis()	+ "Test-Opportunity")
				.with("CloseDate", new Date())
				.with("StageName", "Stage One")
				.build();

		// Create opportunities in sand-boxes and keep track of them for cleaning up afterwards
		opportunitiesCreatedInB.add(createTestOpportunitiesInSfdcSandbox(opportunity, createOpportunityInBFlow));

		// Execution
		executeWaitAndAssertBatchJob(B_INBOUND_FLOW_NAME);

		// Assertions
		Map<String, String> retrievedOpportunityFromA = (Map<String, String>) queryOpportunity(opportunity, queryOpportunityFromAFlow);
		Assert.assertNotNull("There should be some opportunity in org A", retrievedOpportunityFromA);
		
		opportunitiesCreatedInA.add(retrievedOpportunityFromA.get("Id"));
		Assert.assertEquals("Opportunities should be synced", opportunity.get("Name"), retrievedOpportunityFromA.get("Name"));
	}
	
	@Test
	public void testFromAtoBWithoutAccount()
			throws MuleException, Exception {
		// Build test opportunity
		Map<String, Object> opportunityInA = anOpportunity()
				.with("Amount", "123456")
				.with("Name", ANYPOINT_TEMPLATE_NAME + "-" + System.currentTimeMillis()	+ "Test-Opportunity")
				.with("CloseDate", new Date())
				.with("StageName", "Stage Two")
				.build();

		// Create opportunities in sand-boxes and keep track of them for cleaning up afterwards
		opportunitiesCreatedInA.add(createTestOpportunitiesInSfdcSandbox(opportunityInA, createOpportunityInAFlow));

		// Execution
		executeWaitAndAssertBatchJob(A_INBOUND_FLOW_NAME);

		// Assertions
		Map<String, String> retrievedOpportunityFromB = (Map<String, String>) queryOpportunity(opportunityInA, queryOpportunityFromBFlow);
		Assert.assertNotNull("There should be some opportunity in org B", retrievedOpportunityFromB);

		opportunitiesCreatedInB.add(retrievedOpportunityFromB.get("Id"));
		Assert.assertEquals("Opportunities should be synced", opportunityInA.get("Name"), retrievedOpportunityFromB.get("Name"));
	}

	private Object queryOpportunity(Map<String, Object> opportunity,
			InterceptingChainLifecycleWrapper queryOpportunityFlow)
			throws MuleException, Exception {
		return queryOpportunityFlow
				.process(getTestEvent(opportunity, MessageExchangePattern.REQUEST_RESPONSE))
				.getMessage().getPayload();
	}

	private String createTestOpportunitiesInSfdcSandbox(Map<String, Object> opportunity, InterceptingChainLifecycleWrapper createOpportunityFlow)
			throws MuleException, Exception {
		List<Map<String, Object>> salesforceOpportunities = new ArrayList<Map<String, Object>>();
		salesforceOpportunities.add(opportunity);

		final List<SaveResult> payloadAfterExecution = (List<SaveResult>) createOpportunityFlow
				.process(getTestEvent(salesforceOpportunities, MessageExchangePattern.REQUEST_RESPONSE))
				.getMessage().getPayload();
		return payloadAfterExecution.get(0).getId();
	}

	private void executeWaitAndAssertBatchJob(String flowConstructName)
			throws Exception {

		// Execute synchronization
		runSchedulersOnce(flowConstructName);

		// Wait for the batch job execution to finish
		batchTestHelper.awaitJobTermination(TIMEOUT_MILLIS * 1000, 500);
		batchTestHelper.assertJobWasSuccessful();
	}
	

}
