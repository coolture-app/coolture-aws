package com.coolture.user.service;

import com.coolture.user.dto.InterestCatalogDto;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.repository.InterestRepository;
import com.coolture.user.repository.InterestRepository.CategoryEntity;
import com.coolture.user.repository.InterestRepository.InterestEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterestServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private final InterestRepository repository = mock(InterestRepository.class);
    private final InterestService service = new InterestService(repository, 2, 3);

    @BeforeEach
    void catalogue() throws SQLException {
        when(repository.findCategories()).thenReturn(List.of(
                new CategoryEntity("music", "Muzyka", "Music"),
                new CategoryEntity("art", "Sztuka", "Art")
        ));
        when(repository.findInterests()).thenReturn(List.of(
                new InterestEntity("jazz", "music", "Jazz", "Jazz"),
                new InterestEntity("rock", "music", "Rock", "Rock"),
                new InterestEntity("opera", "music", "Opera", "Opera"),
                new InterestEntity("hip_hop", "music", "Hip-hop", "Hip-hop")
        ));
    }

    @Test
    void getCatalog_groupsInterestsUnderCategories() throws SQLException {
        InterestCatalogDto catalog = service.getCatalog();

        assertEquals(2, catalog.minSelections());
        assertEquals(3, catalog.maxSelections());
        assertEquals(List.of("music", "art"), catalog.categories().stream().map(c -> c.key()).toList());
        assertEquals(List.of("jazz", "rock", "opera", "hip_hop"),
                catalog.categories().getFirst().interests().stream().map(i -> i.key()).toList());
        assertEquals(List.of(), catalog.categories().get(1).interests());
    }

    @Test
    void replaceSelection_storesInterests() throws SQLException {
        service.replaceSelection(USER_ID, USER_ID, request("jazz", "rock"));

        verify(repository).replaceSelection(USER_ID, Set.of("jazz", "rock"));
    }

    @Test
    void replaceSelection_rejectsCategoryKey() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request("music", "jazz", "rock")));

        verify(repository, never()).replaceSelection(any(), any());
    }

    @Test
    void replaceSelection_rejectsTooFewInterests() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request("jazz")));

        verify(repository, never()).replaceSelection(any(), any());
    }

    @Test
    void replaceSelection_rejectsTooManyInterests() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request("jazz", "rock", "opera", "hip_hop")));

        verify(repository, never()).replaceSelection(any(), any());
    }

    @Test
    void replaceSelection_countsRepeatedKeyOnce() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request("jazz", "jazz")));
    }

    @Test
    void replaceSelection_rejectsUnknownKey() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request("jazz", "rock", "curling")));

        verify(repository, never()).replaceSelection(any(), any());
    }

    @Test
    void replaceSelection_rejectsOtherUser() throws SQLException {
        assertThrows(SecurityException.class,
                () -> service.replaceSelection(USER_ID, UUID.randomUUID(), request("jazz", "rock")));

        verify(repository, never()).replaceSelection(eq(USER_ID), any());
    }

    @Test
    void getSelection_rejectsOtherUser() {
        assertThrows(SecurityException.class, () -> service.getSelection(USER_ID, UUID.randomUUID()));
    }

    private static UserInterestsDto request(String... keys) {
        return new UserInterestsDto(List.of(keys));
    }
}
