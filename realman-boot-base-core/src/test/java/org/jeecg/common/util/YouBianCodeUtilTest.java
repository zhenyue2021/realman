package org.jeecg.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YouBianCodeUtilTest {

    @Test
    void shouldAppendFirstChildCodeWhenLocalCodeIsDifferentEmptyStringInstance() {
        String result = YouBianCodeUtil.getSubYouBianCode("A01", new String(""));
        assertEquals("A01A01", result);
    }
}
