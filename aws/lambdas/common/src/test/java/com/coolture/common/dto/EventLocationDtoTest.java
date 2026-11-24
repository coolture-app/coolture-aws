package com.coolture.common.dto;

import com.coolture.common.json.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventLocationDtoTest {

    private static EventLocationDto read(String countryCode) {
        String code = countryCode == null ? "null" : "\"" + countryCode + "\"";
        return JsonUtils.fromJsonAndValidate("""
            {"countryCode": %s, "city": "Gdańsk", "coordinates": {"latitude": 54.38, "longitude": 18.64}}
            """.formatted(code), EventLocationDto.class);
    }

    @Test
    void keepsATwoLetterCode() {
        assertEquals("PL", read("PL").countryCode());
    }

    @Test
    void turnsAThreeLetterCodeFromAnOldFrontendIntoTwoLetters() {
        assertEquals("PL", read("POL").countryCode());
        assertEquals("DE", read("deu").countryCode());
    }

    @Test
    void needsNoCountryOrPostalCode() {
        EventLocationDto location = read(null);

        assertNull(location.countryCode());
        assertNull(location.postalCode());
        assertNull(read("").countryCode());
    }

    @Test
    void rejectsAnUnknownCode() {
        assertThrows(IllegalArgumentException.class, () -> read("XYZ"));
        assertThrows(IllegalArgumentException.class, () -> read("P"));
    }

    @Test
    void needsACity() {
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.fromJsonAndValidate(
            "{\"coordinates\": {\"latitude\": 54.38, \"longitude\": 18.64}}", EventLocationDto.class));
    }
}
