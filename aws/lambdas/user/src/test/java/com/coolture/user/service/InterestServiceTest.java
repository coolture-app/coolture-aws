package com.coolture.user.service;

import com.coolture.user.dto.InterestCatalogDto;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.repository.InterestRepository;
import com.coolture.user.repository.InterestRepository.CategoryEntity;
import com.coolture.user.repository.InterestRepository.InterestEntity;
import com.coolture.user.repository.InterestRepository.KindEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterestServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final List<String> A_KIND = List.of("concerts");

    private final InterestRepository repository = mock(InterestRepository.class);
    private final InterestService service = new InterestService(repository, 0, 3);

    @BeforeEach
    void catalogue() throws SQLException {
        when(repository.findKinds()).thenReturn(List.of(
                new KindEntity("concerts", "Koncerty", "Concerts"),
                new KindEntity("stage", "Scena", "Stage")
        ));
        when(repository.findCategories()).thenReturn(List.of(
                new CategoryEntity("music", "Muzyka", "Music"),
                new CategoryEntity("art", "Sztuka", "Art"),
                new CategoryEntity("wellbeing", "Styl życia", "Lifestyle")
        ));
        when(repository.findInterests()).thenReturn(List.of(
                new InterestEntity("jazz", "music", "Jazz", "Jazz", true),
                new InterestEntity("rock", "music", "Rock", "Rock", true),
                new InterestEntity("opera", "music", "Opera", "Opera", true),
                new InterestEntity("hip_hop", "music", "Hip-hop", "Hip-hop", true),
                new InterestEntity("astrology", "wellbeing", "Astrologia", "Astrology", false)
        ));
    }

    @Test
    void getCatalog_listsKinds_thenOfferedInterestsUnderCategoriesThatHaveAny() throws SQLException {
        InterestCatalogDto catalog = service.getCatalog();

        assertEquals(List.of(1, 0, 3), List.of(catalog.minKinds(), catalog.minInterests(), catalog.maxInterests()));
        assertEquals(List.of("concerts", "stage"), catalog.kinds().stream().map(k -> k.key()).toList());
        assertEquals(List.of("music"), catalog.categories().stream().map(c -> c.key()).toList());
        assertEquals(List.of("jazz", "rock", "opera", "hip_hop"),
                catalog.categories().getFirst().interests().stream().map(i -> i.key()).toList());
    }

    @Test
    void replaceSelection_storesKindsAndInterests() throws SQLException {
        service.replaceSelection(USER_ID, USER_ID, request(List.of("stage", "concerts"), "jazz", "rock"));

        verify(repository).replaceSelection(USER_ID, Set.of("stage", "concerts"), Set.of("jazz", "rock"));
    }

    @Test
    void replaceSelection_takesAKindWithNoInterests() throws SQLException {
        service.replaceSelection(USER_ID, USER_ID, request(A_KIND));

        verify(repository).replaceSelection(USER_ID, Set.of("concerts"), Set.of());
    }

    @Test
    void replaceSelection_rejectsNoKind() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request(List.of(), "jazz")));

        verify(repository, never()).replaceSelection(any(), any(), any());
    }

    @Test
    void replaceSelection_rejectsUnknownKind_orAnInterestKeyAsAKind() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request(List.of("jazz"))));

        verify(repository, never()).replaceSelection(any(), any(), any());
    }

    @Test
    void replaceSelection_rejectsCategoryKey() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request(A_KIND, "music", "jazz")));

        verify(repository, never()).replaceSelection(any(), any(), any());
    }

    @Test
    void replaceSelection_rejectsTooManyInterests() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request(A_KIND, "jazz", "rock", "opera", "hip_hop")));

        verify(repository, never()).replaceSelection(any(), any(), any());
    }

    @Test
    void replaceSelection_countsRepeatedKeyOnce() throws SQLException {
        service.replaceSelection(USER_ID, USER_ID, request(List.of("stage", "stage"), "jazz", "jazz", "rock", "opera"));

        verify(repository).replaceSelection(USER_ID, Set.of("stage"), Set.of("jazz", "rock", "opera"));
    }

    @Test
    void replaceSelection_rejectsUnknownKey() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request(A_KIND, "jazz", "curling")));

        verify(repository, never()).replaceSelection(any(), any(), any());
    }

    @Test
    void replaceSelection_rejectsAHiddenInterest_evenOneTheUserHolds() throws SQLException {
        when(repository.findSelectedKeys(USER_ID)).thenReturn(List.of("astrology"));

        assertThrows(IllegalArgumentException.class,
                () -> service.replaceSelection(USER_ID, USER_ID, request(A_KIND, "astrology", "jazz")));

        verify(repository, never()).replaceSelection(any(), any(), any());
    }

    @Test
    void replaceSelection_rejectsOtherUser() throws SQLException {
        assertThrows(SecurityException.class,
                () -> service.replaceSelection(USER_ID, UUID.randomUUID(), request(A_KIND, "jazz")));

        verify(repository, never()).replaceSelection(eq(USER_ID), any(), any());
    }

    @Test
    void getSelection_readsAnyonesKindsAndInterests() throws SQLException {
        when(repository.findSelectedKinds(USER_ID)).thenReturn(List.of("concerts"));
        when(repository.findSelectedKeys(USER_ID)).thenReturn(List.of("jazz", "opera"));

        assertEquals(new UserInterestsDto(List.of("concerts"), List.of("jazz", "opera")), service.getSelection(USER_ID, null));
        verify(repository, never()).userExists(any());
    }

    @Test
    void getSelection_isEmptyForSomeoneWhoPickedNothing_andNotFoundForNobody() throws SQLException {
        UUID nobody = UUID.randomUUID();
        when(repository.findSelectedKinds(any())).thenReturn(List.of());
        when(repository.findSelectedKeys(any())).thenReturn(List.of());
        when(repository.userExists(USER_ID)).thenReturn(true);

        assertEquals(new UserInterestsDto(List.of(), List.of()), service.getSelection(USER_ID, null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.getSelection(nobody, null));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void getSelection_isNotFound_acrossABlock() throws SQLException {
        UUID caller = UUID.randomUUID();
        when(repository.isBlockedPair(USER_ID, caller)).thenReturn(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.getSelection(USER_ID, caller));

        assertTrue(e.getMessage().contains("not found"));
        verify(repository, never()).findSelectedKeys(any());
    }

    private static UserInterestsDto request(List<String> kinds, String... interests) {
        return new UserInterestsDto(kinds, List.of(interests));
    }
}
