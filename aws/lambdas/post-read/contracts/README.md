# Feed and post list contract

How the frontend and backend agree on the home feed and the post lists.

| File | Holds |
|---|---|
| [`feed-api.yaml`](feed-api.yaml) | Shapes, parameters and examples (OpenAPI 3.0.3) |
| This file | What a schema can't express: feed rules, paging, past events and errors |

Read both. To view the YAML, paste it into [editor.swagger.io](https://editor.swagger.io) or use an OpenAPI extension in VS Code.

This folder is inside `post-read` because that Lambda serves everything described here, and because `sam build` runs the tests on a copy of this module alone. `ContractTest` can read the file there too, and the file doesn't reach the Lambda package.

## Two ways to read posts

| | `GET /feed` | `GET /posts` |
|---|---|---|
| **Used by** | The home page's "Dla Ciebie" tab, with or without search and filters | The "Wszystkie" tab, My Events, profiles |
| **Input** | First page: `limit`, location, filters. Later pages: `cursor`, `limit`, the same filters | All filters and sorts, plus `includePast` |
| **Contains** | Posts new to this viewer, ranked, narrowed by the filters | Everything visible, minus old events by default |
| **Order** | Score, or upcoming if ranking fails | `RECENT`, `POPULAR`, `UPCOMING` or `NEAREST` |

**The filters mean the same everywhere:** `q`, `authorId`, `status`, `followed`, `type`, `from`, `to`,
`includePast`, and `radiusKm` around `latitude`/`longitude`. One piece of SQL applies them to `/feed`, `/posts` and `/posts/map`.

**Dates match by overlap.** A post matches `from` and `to` if it runs at any moment between them: `starts_at <= to` and its
end `>= from`, either side optional. An exhibition running all month matches a weekend's dates.

**An event's end** is `ends_at`, or `starts_at + 3 hours` when there's none, or never when `openEnded` is true (no end date,
"do odwołania"). Everywhere in this document. A post with `openEnded` has no `endsAt`. *Running* means started and not
ended. `POST /posts` and `PATCH /posts/{id}` take `openEnded`; `PATCH` follows merge-patch rules, so `"endsAt": null` removes
the end.

`GET /posts/map` takes the same filters as `/posts`, plus the map bounds (`leftUpper.latitude`, `leftUpper.longitude`,
`rightBottom.latitude`, `rightBottom.longitude`), and `excludeMine=true` to leave out what the feed leaves out.

**One post:** `GET /posts/{id}` and `GET /posts/{postId}/friends` answer 404 when the viewer and the author are a blocked pair,
the same as for a deleted post, so nothing tells either who blocked whom.

**Friends:** a card's `friendStances` names at most 2 friends per stance and counts the rest. `GET /posts/{postId}/friends`
lists them all, for the list behind the card's friends line.

**Photos:** every `PostCard` has `photos`, all of the post's photos with the cover first, at most 8. A post can't have more than 8
(`PostLimits.MAX_PHOTOS` in `common`; `POST` and `PUT /posts` return `400` above it).
`GET /posts/recommendations` no longer exists.

## `GET /feed`

```
GET /feed?limit=20&latitude=54.35&longitude=18.65     first page
GET /feed?limit=20&cursor=eyJ2Ijox...                 every later page
```

```json
{
  "mode": "RANKED",
  "items": [ { "id": "…", "title": "…", "startsAt": "…" } ],
  "page": { "limit": 20, "hasMore": true, "nextCursor": "eyJ2Ijox..." }
}
```

- **Authentication is optional.** No token means guest. A rejected token gets a denial from the gateway, never the guest view.
- **`items`** are the same `PostCard` objects as `/posts`.
- **`page`** has the same shape as other lists. `nextCursor` is left out when `hasMore` is false.
- **`mode`** says how the page was ordered: `RANKED` or `UPCOMING`. The UI behaves the same for both, and treats an unknown value
  like `RANKED`, so new modes can be added. It exists for logs, tests and evaluation.
- **Location** is optional. `latitude` and `longitude` come together or the request gets `400`. The frontend rounds them to 2 decimals
  and the server rounds again. For proximity, the first page's position stays in the cursor. With `radiusKm`, every page sends
  the position again, as the radius's centre. With no position, a signed-in viewer's home (`PUT /users/{userId}/home` in the
  users contract) takes its place in the cursor; a guest or someone with no home gets neutral proximity.
- **Filters** are sent with every page, like `/posts`, and narrow which posts are ranked. They never show what the rules below
  hide, except that `includePast`, `from` or `to` let ended events in.

### What the feed may show

The fallback order uses exactly the same rules.

| A post appears in `/feed` only if | Signed in | Guest |
|---|---|---|
| Not deleted | Yes | Yes |
| Event hasn't ended, checked against the real time on every page, unless `includePast`, `from` or `to` is set | Yes | Yes |
| Created at or before the session's `asOf` | Yes | Yes |
| Public | Yes | Yes |
| Or friends-only, and the viewer follows the author | Yes | No |
| Not the viewer's own post | Yes | n/a |
| Not liked, saved, attending or marked "Nie dla mnie" | Yes | n/a |
| No block between viewer and author, in either direction | Yes | n/a |

Commenting doesn't hide a post. A post liked during a session stays where it is, and leaves on the next refresh.

### When ranking can't run

| Situation | Result |
|---|---|
| User or post has no vector, or their vectors come from different embedding models | `RANKED`, similarity is neutral |
| No location, or an online post | `RANKED`, distance is neutral |
| No PageRank score for the viewer and post, or no run finished within `PPR_RUN_MAX_AGE_HOURS` before the session | `RANKED`, the PageRank part is neutral |
| Guest | `RANKED`, on time, popularity and location |
| Ranking query fails or takes over 5 seconds, or scoring throws | `200` with `mode: UPCOMING` in the order of `sortBy=UPCOMING` below, and a server log line with the reason |
| The upcoming query also fails, or the database is down | `500` |

## `GET /posts` and `GET /posts/map`

All existing parameters keep their meaning. What's specific to this contract:

| Parameter or behaviour | Rule |
|---|---|
| `includePast` | Default `false`. See the table below |
| `sortBy` | `RECENT`, `POPULAR`, `UPCOMING` or `NEAREST`. Anything else, including `RECOMMENDED`, returns `400` |
| `NEAREST` | Needs `latitude` and `longitude`, else `400`. Only posts with a place |
| `cursor` | Continues the sort it was created for. Using it with a different sort returns `400` |
| Order | `RECENT`: `created_at, id` descending. `POPULAR`: popularity's count, likes + Interesuje mnie + Biorę udział, then `id`, descending. `UPCOMING`: below. `NEAREST`: metres from the position, `id` ascending |

### Past events

| Request | Shows |
|---|---|
| Default, any filter or search | Events not ended, or ended up to 12 hours ago |
| `includePast=true` | All visible events |
| `from` or `to` set | All visible events running in that window, because a chosen date is explicit |
| `sortBy=UPCOMING` | Never an ended event, even with `includePast=true` or a date filter |
| My Events | The frontend sends `includePast=true`, so attended events stay |

Blocks apply here too, in both directions, for signed-in viewers. A guest has no blocks.

### `sortBy=UPCOMING`

Two streams, merged in a steady rhythm. The moment `m` is the later of the list's `asOf` (the first page's time, kept in the
cursor) and `from`.

| Stream | Holds | Order |
|---|---|---|
| Dated | Events starting at or after `m` | `starts_at, id` ascending |
| Running | Events started before `m` and not ended at `m` | End ascending, `id` ascending; no end date last |

Every 5th card (the 5th, 10th, ...) comes from the running stream, the others from the dated stream. When one stream runs
out, the other continues alone. The filters apply to both. `/feed`'s `UPCOMING` fallback uses the same merge.

## Paging

**Only `hasMore: false` ends a list.** A short or empty page doesn't.

Cursors are opaque to the frontend: send `nextCursor` back unchanged, and never parse it. The format can change without a contract
change.

### Feed session

The first request starts a session by choosing a time, `asOf`, and a random seed. Everything that affects the order goes into the
cursor, so nothing is stored on the server. A new first request starts a new session, which is what makes page 1 change between
refreshes.

Scores use `asOf` and not the current time, so the order doesn't drift while someone scrolls. The viewer's taste counts their likes,
interested and going up to `asOf`, so their own reactions reach the feed from the next session and never reorder the current one.
Other people's likes and stances can still move scores between pages:

| Effect | Handling |
|---|---|
| Repeated post | The frontend drops ids it has already shown in this session |
| Skipped post | Accepted. Nothing records it as seen, so it can return on the next refresh |
| Page with few or no new posts | Normal. If nothing new was added and `hasMore` is true, the frontend fetches again |
| Ranking fails mid-scroll | The response is `UPCOMING` from the top of that order, and dedupe removes what was shown |

### `GET /posts`

Each sort pages on its own key, and the key is in the cursor:

| Sort | A later page has |
|---|---|
| `RECENT` | `(created_at, id)` below the cursor |
| `POPULAR` | `(count, id)` below the cursor's post, whose count is taken again. If it changed since, a few posts repeat or are skipped, as in the feed |
| `UPCOMING` | `asOf`, both streams' last keys and how many cards came so far |
| `NEAREST` | `(metres, id)` above the cursor's post, whose distance is measured again from the request's position |

Other lists, such as comments and followers, keep their own cursors. The rule about `hasMore` is the same.

## Errors

Errors have the shape `{"error": "message"}`.

| Status | When | Frontend does |
|---|---|---|
| `400` | Unreadable cursor, wrong-sort cursor, unknown `sortBy`, `NEAREST` without a position, non-numeric `limit`, location out of range or half-sent, invalid `includePast` or `excludeMine`, more than 8 photos on a post | Restarts the list once from page 1. A second `400` shows the retry message, so it can't loop |
| `401`, `403` | Token rejected | Existing auth handling |
| `429` | Over the API throttle | Shows retry |
| `500` | Database or Lambda failure, also after the fallback | Keeps what's shown and offers a retry |

A `limit` above 100 is capped, and zero or below uses 20.

## What keeps this honest

| Piece | Keeps honest |
|---|---|
| `ContractTest` in `post-read` | The API matches `feed-api.yaml`, including no undocumented fields |
| `PostReadDbTest` in `post-read` (`make test-db`) | The queries follow the rules in this file |
| `npm run api-types` in the frontend | Regenerates TypeScript types, and a compile check fails if frontend models disagree |
| A pull request | The other developer reviews the change |

## Changing the contract

1. Change `contracts/` first, in its own small pull request, reviewed by another developer. No implementation pull request changes
   the contract's meaning on its own.
2. Adding an optional field or a new `mode` value doesn't need discussion beyond review. Removing or renaming anything, or changing a
   rule, needs agreement first.
3. Run `npm run api-types` in the frontend after any contract change, and commit the result.

Once a contract pull request is merged, one person can build the backend and another the frontend without waiting for each other.
