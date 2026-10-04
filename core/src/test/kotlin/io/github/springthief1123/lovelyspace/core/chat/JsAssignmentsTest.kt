package io.github.springthief1123.lovelyspace.core.chat

import io.github.springthief1123.lovelyspace.core.chat.JsAssignments.Value
import org.junit.Assert.assertEquals
import org.junit.Test

class JsAssignmentsTest {
    @Test
    fun readsChainedAssignmentsAndScalars() {
        val v = JsAssignments.parse("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\na.b = size = 12; flag=true;none = null;neg=-3;")
        assertEquals(Value.Num(12.0), v["a.b"])
        assertEquals(Value.Num(12.0), v["size"])
        assertEquals(Value.Bool(true), v["flag"])
        assertEquals(Value.Null, v["none"])
        assertEquals(Value.Num(-3.0), v["neg"])
    }

    @Test
    fun unescapesStrings() {
        val v = JsAssignments.parse("""s = 'a\x3eb\/c\'d\\eあ\nf'; t = "x;y";""")
        assertEquals(Value.Str("a>b/c'd\\eあ\nf"), v["s"])
        assertEquals("セミコロンを含む文字列で文が切れない", Value.Str("x;y"), v["t"])
    }

    @Test
    fun readsArrays() {
        val v = JsAssignments.parse("l = new Array('a', '', 'c'); m = new Array(); n = ['x'];")
        assertEquals(Value.Arr(listOf(Value.Str("a"), Value.Str(""), Value.Str("c"))), v["l"])
        assertEquals(Value.Arr(emptyList()), v["m"])
        assertEquals(Value.Arr(listOf(Value.Str("x"))), v["n"])
    }

    @Test
    fun skipsUnknownExpressions() {
        val v = JsAssignments.parse("x = foo(1, ';'); y = 2;")
        assertEquals(null, v["x"])
        assertEquals(Value.Num(2.0), v["y"])
    }
}
