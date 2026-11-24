package com.coolture.common.dto;

import com.coolture.common.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostLinkDtoTest {

    private static PostLinkDto read(String json) {
        return JsonUtils.fromJsonAndValidate(json, PostLinkDto.class);
    }

    @Test
    void aBlankLabelIsNone_andALabelLosesItsSpaces() {
        assertNull(read("{\"url\": \"https://a.example\", \"label\": \"   \"}").label());
        assertEquals("Bilety", read("{\"url\": \"https://a.example\", \"label\": \" Bilety \"}").label());
    }

    @Test
    void aLabelIsMeasuredWithoutItsSpaces() {
        String forty = "x".repeat(40);
        assertEquals(forty, read("{\"url\": \"https://a.example\", \"label\": \"  " + forty + "  \"}").label());
    }

    @Test
    void refusesAUrlThatIsNotHttp() {
        assertThrows(IllegalArgumentException.class, () -> read("{\"url\": \"javascript:alert(1)\"}"));
        assertThrows(IllegalArgumentException.class, () -> read("{\"url\": \"https://a b.example\"}"));
    }
}
