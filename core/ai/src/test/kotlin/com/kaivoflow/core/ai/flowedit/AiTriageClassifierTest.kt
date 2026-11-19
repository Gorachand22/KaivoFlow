package com.kaivoflow.core.ai.flowedit

import org.junit.Assert.assertEquals
import org.junit.Test

class AiTriageClassifierTest {

    @Test
    fun `classifies greetings correctly`() {
        assertEquals(PromptIntent.Greeting, AiTriageClassifier.classify("hi"))
        assertEquals(PromptIntent.Greeting, AiTriageClassifier.classify("Hello there!"))
        assertEquals(PromptIntent.Greeting, AiTriageClassifier.classify("good morning"))
        assertEquals(PromptIntent.Greeting, AiTriageClassifier.classify("hey"))
    }

    @Test
    fun `classifies knowledge questions correctly`() {
        assertEquals(PromptIntent.Knowledge, AiTriageClassifier.classify("How does a webhook trigger work?"))
        assertEquals(PromptIntent.Knowledge, AiTriageClassifier.classify("Why did my battery trigger fail?"))
        assertEquals(PromptIntent.Knowledge, AiTriageClassifier.classify("What is the difference between schedule and manual?"))
        assertEquals(PromptIntent.Knowledge, AiTriageClassifier.classify("Can you explain expressions to me?"))
        assertEquals(PromptIntent.Knowledge, AiTriageClassifier.classify("What steps are available for notifications?"))
    }

    @Test
    fun `classifies workflow mutations correctly`() {
        assertEquals(PromptIntent.WorkflowMutation, AiTriageClassifier.classify("Create a flow that alerts me when battery is low"))
        assertEquals(PromptIntent.WorkflowMutation, AiTriageClassifier.classify("Add a Slack notification step"))
        assertEquals(PromptIntent.WorkflowMutation, AiTriageClassifier.classify("Connect step 1 to step 2"))
        assertEquals(PromptIntent.WorkflowMutation, AiTriageClassifier.classify("Change the notification title to Urgent"))
        assertEquals(PromptIntent.WorkflowMutation, AiTriageClassifier.classify("Build a webhook that receives order data"))
        assertEquals(PromptIntent.WorkflowMutation, AiTriageClassifier.classify("Fix the broken connection between steps"))
    }
}
