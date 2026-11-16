package com.coolture.common.db;

import com.coolture.common.dto.FriendStancesDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.storage.MediaUrls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Friends are mutual follows. Following needs no approval, so one-way follows reveal nothing. A block replaces the
 * blocker's follow (one row per pair), so it ends a friendship too.
 */
public final class Friends {

    /** Names per stance on a card. The rest are only counted. */
    public static final int NAMES_PER_STANCE = 2;

    private Friends() {}

    /** Whether the user in {@code userColumn} is the viewer's friend and shares stances. Binds the viewer twice. */
    public static String sharingFriend(String userColumn) {
        return "(EXISTS (SELECT 1 FROM user_relations fa WHERE fa.source_user_id = ?::uuid AND fa.target_user_id = " + userColumn
            + " AND fa.type = 'FOLLOW')"
            + " AND EXISTS (SELECT 1 FROM user_relations fb WHERE fb.source_user_id = " + userColumn
            + " AND fb.target_user_id = ?::uuid AND fb.type = 'FOLLOW')"
            + " AND EXISTS (SELECT 1 FROM users fu WHERE fu.id = " + userColumn + " AND fu.share_stances))";
    }

    /** Each post's friend stances, with one query for the whole list. Empty for a guest. */
    public static Map<UUID, FriendStancesDto> forPosts(Connection conn, UUID viewer, Collection<UUID> postIds) throws SQLException {
        if (viewer == null || postIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
            SELECT post_id, type, total, user_id, username, first_name, last_name, avatar_key
            FROM (
                SELECT pp.post_id, pp.type, u.id AS user_id, u.username, u.first_name, u.last_name,
                       avatar_m.object_key AS avatar_key,
                       ROW_NUMBER() OVER (PARTITION BY pp.post_id, pp.type ORDER BY pp.created_at DESC, u.id) AS n,
                       COUNT(*) OVER (PARTITION BY pp.post_id, pp.type) AS total
                FROM post_participations pp
                JOIN users u ON u.id = pp.user_id
                LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
                LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
                WHERE pp.post_id = ANY(?::uuid[])
                  AND pp.type IN ('INTERESTED', 'TAKES_PART')
                  AND """ + sharingFriend("pp.user_id") + """

            ) f
            WHERE n <= ?
            ORDER BY post_id, type, n
            """;

        Map<UUID, Map<String, List<FriendStancesDto.Friend>>> people = new HashMap<>();
        Map<UUID, Map<String, Integer>> totals = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            new ParamBinder(stmt, conn)
                .bindUUIDArray(postIds.toArray(new UUID[0]))
                .bindUUID(viewer)
                .bindUUID(viewer)
                .bindInt(NAMES_PER_STANCE);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UUID postId = (UUID) rs.getObject("post_id");
                    String type = rs.getString("type");
                    totals.computeIfAbsent(postId, id -> new HashMap<>()).put(type, rs.getInt("total"));
                    people.computeIfAbsent(postId, id -> new HashMap<>())
                        .computeIfAbsent(type, t -> new ArrayList<>())
                        .add(new FriendStancesDto.Friend(
                            (UUID) rs.getObject("user_id"),
                            rs.getString("username"),
                            rs.getString("first_name"),
                            rs.getString("last_name"),
                            MediaUrls.presign(rs.getString("avatar_key"))));
                }
            }
        }

        Map<UUID, FriendStancesDto> byPost = new HashMap<>();
        people.forEach((postId, byType) -> byPost.put(postId, new FriendStancesDto(
            group(byType, totals.get(postId), "TAKES_PART"),
            group(byType, totals.get(postId), "INTERESTED"))));
        return byPost;
    }

    public static List<PostCardDto> withFriendStances(Connection conn, UUID viewer, List<PostCardDto> cards) throws SQLException {
        Map<UUID, FriendStancesDto> byPost = forPosts(conn, viewer, cards.stream().map(PostCardDto::id).toList());
        return byPost.isEmpty() ? cards : cards.stream().map(card -> card.withFriendStances(byPost.get(card.id()))).toList();
    }

    private static FriendStancesDto.Group group(Map<String, List<FriendStancesDto.Friend>> byType, Map<String, Integer> totals, String type) {
        List<FriendStancesDto.Friend> list = byType.get(type);
        return list == null ? null : new FriendStancesDto.Group(totals.get(type), list);
    }
}
