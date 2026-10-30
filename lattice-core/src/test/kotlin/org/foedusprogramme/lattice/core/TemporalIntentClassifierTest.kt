package org.foedusprogramme.lattice.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Static-regex routing of a cue to a [TemporalIntent] — no embedding, graph, or clock involved. */
class TemporalIntentClassifierTest {

    private fun c(cue: String) = TemporalIntentClassifier.classify(cue)

    @Test
    fun bareBackReferencesArePeriod() {
        assertEquals(TemporalIntent.PERIOD, c("那阵子还聊了什么"))
        assertEquals(TemporalIntent.PERIOD, c("前段时间我们说到的那件事"))
        assertEquals(TemporalIntent.PERIOD, c("那会儿还有什么"))
        assertEquals(TemporalIntent.PERIOD, c("what else did we talk about back then"))
        assertEquals(TemporalIntent.PERIOD, c("around that time, anything else?"))
    }

    @Test
    fun timeQualifiedTopicalQueriesAreRelevance() {
        assertEquals(TemporalIntent.RELEVANCE, c("2025年3月我们聊了什么"))
        assertEquals(TemporalIntent.RELEVANCE, c("2025-03 的项目进展"))
        assertEquals(TemporalIntent.RELEVANCE, c("上个月的那个决定"))
        assertEquals(TemporalIntent.RELEVANCE, c("三个月前发生的事"))
        assertEquals(TemporalIntent.RELEVANCE, c("what happened around March"))
        assertEquals(TemporalIntent.RELEVANCE, c("the deadline last month"))
        assertEquals(TemporalIntent.RELEVANCE, c("3 months ago I changed jobs"))
    }

    @Test
    fun plainTopicalQueriesAreNone() {
        assertEquals(TemporalIntent.NONE, c("我喜欢喝什么咖啡"))
        assertEquals(TemporalIntent.NONE, c("what programming language do I use"))
        assertEquals(TemporalIntent.NONE, c("项目截止日期是哪一天")) // "哪一天" carries no resolvable token
    }

    @Test
    fun periodWinsOverRelevanceWhenBothPresent() {
        // A contrived cue carrying a concrete date AND a back-reference phrase routes to PERIOD.
        assertEquals(TemporalIntent.PERIOD, c("2025-03 那阵子我们还聊了什么"))
    }
}
