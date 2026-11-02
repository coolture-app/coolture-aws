# Feed and post list contract

How the frontend and backend agree on the home feed and the post lists.

| File | Holds |
|---|---|
| [`feed-api.yaml`](feed-api.yaml) | Shapes, parameters and examples (OpenAPI 3.0.3) |
| This file | What a schema can't express: feed rules, paging, past events and errors |

Read both. To view the YAML, paste it into [editor.swagger.io](https://editor.swagger.io) or use an OpenAPI extension in VS Code.

## Two ways to read posts

| | `GET /feed` | `GET /posts` |
|---|---|---|
| **Used by** | Home page with no search or filter | Search, filters, Recent, Popular and Upcoming sorts, My Events |
| **Input** | First page: `limit`, location. Later pages: `cursor`, `limit` | All filters and sorts, plus `includePast` |
| **Contains** | Live posts ranked for this viewer | Everything visible, minus old events by default |
| **Order** | Score, or upcoming if ranking fails | `RECENT`, `POPULAR` or `UPCOMING` |

`GET /posts/map` takes the same filters as `/posts`, plus the map bounds. `GET /posts/{id}` doesn't change.
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
  and the server rounds again. On later pages they stay in the cursor, so they aren't sent again.

### What the feed may show

The fallback order uses exactly the same rules.

An event's end is `COALESCE(ends_at, starts_at + 3 hours)`, everywhere in this document.

| A post appears in `/feed` only if | Signed in | Guest |
|---|---|---|
| Not deleted | Yes | Yes |
| Event hasn't ended, checked against the real time on every page | Yes | Yes |
| Created at or before the session's `asOf` | Yes | Yes |
| Public | Yes | Yes |
| Or friends-only, and the viewer follows the author | Yes | No |
| Not the viewer's own post | Yes | n/a |
| Not liked, disliked, interested or attending | Yes | n/a |
| No block between viewer and author, in either direction | Yes | n/a |

Commenting doesn't hide a post. A post liked during a session stays where it is, and leaves on the next refresh.

### When ranking can't run

| Situation | Result |
|---|---|
| User or post has no vector | `RANKED`, similarity is neutral |
| No location, or an online post | `RANKED`, distance is neutral |
| Guest | `RANKED`, on time, popularity and location |
| Ranking query fails or takes over 5 seconds, or scoring throws | `200` with `mode: UPCOMING`, and a server log line with the reason |
| The upcoming query also fails, or the database is down | `500` |

## `GET /posts` and `GET /posts/map`

All existing parameters keep their meaning. What's specific to this contract:

| Parameter or behaviour | Rule |
|---|---|
| `includePast` | Default `false`. See the table below |
| `sortBy` | `RECENT`, `POPULAR` or `UPCOMING`. Anything else, including `RECOMMENDED`, returns `400` |
| `cursor` | Continues the sort it was created for. Using it with a different sort returns `400` |
| Order | `RECENT`: `created_at, id` descending. `POPULAR`: likes, `id` descending. `UPCOMING`: `starts_at, id` ascending |

### Past events

| Request | Shows |
|---|---|
| Default, any filter or search | Events not ended, or ended up to 12 hours ago |
| `includePast=true` | All visible events |
| `startsFrom` or `startsTo` set | All visible events matching those dates, because a chosen date is explicit |
| `sortBy=UPCOMING` | Never an ended event, even with `includePast=true` or a date filter |
| My Events | The frontend sends `includePast=true`, so attended events stay |

Blocks apply here too, in both directions, for signed-in viewers. A guest has no blocks.

## Paging

**Only `hasMore: false` ends a list.** A short or empty page doesn't.

Cursors are opaque to the frontend: send `nextCursor` back unchanged, and never parse it. The format can change without a contract
change.

### Feed session

The first request starts a session by choosing a time, `asOf`, and a random seed. Everything that affects the order goes into the
cursor, so nothing is stored on the server. A new first request starts a new session, which is what makes page 1 change between
refreshes.

Scores use `asOf` and not the current time, so the order doesn't drift while someone scrolls. Likes from other people, and changes
to the viewer's own vector, can still move scores between pages:

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
| `POPULAR` | `(likes, id)` below the cursor |
| `UPCOMING` | `(starts_at, id)` above the cursor |

Other lists, such as comments and followers, keep their own cursors. The rule about `hasMore` is the same.

## Errors

Errors have the shape `{"error": "message"}`.

| Status | When | Frontend does |
|---|---|---|
| `400` | Unreadable cursor, wrong-sort cursor, unknown `sortBy`, non-numeric `limit`, location out of range or half-sent, invalid `includePast` | Restarts the list once from page 1. A second `400` shows the retry message, so it can't loop |
| `401`, `403` | Token rejected | Existing auth handling |
| `429` | Over the API throttle | Shows retry |
| `500` | Database or Lambda failure, also after the fallback | Keeps what's shown and offers a retry |

A `limit` above 100 is capped, and zero or below uses 20.

## What keeps this honest

| Piece | Keeps honest |
|---|---|
| `ContractTest` in `post-read` | The API matches `feed-api.yaml`, including no undocumented fields |
| `PostReadDbTest` in `post-read` (`make test-post-read-db`) | The queries follow the rules in this file |
| `npm run api-types` in the frontend | Regenerates TypeScript types, and a compile check fails if frontend models disagree |
| A pull request | The other developer reviews the change |

## Changing the contract

1. Change `contracts/` first, in its own small pull request, reviewed by another developer. No implementation pull request changes
   the contract's meaning on its own.
2. Adding an optional field or a new `mode` value doesn't need discussion beyond review. Removing or renaming anything, or changing a
   rule, needs agreement first.
3. Run `npm run api-types` in the frontend after any contract change, and commit the result.

Once a contract pull request is merged, one person can build the backend and another the frontend without waiting for each other.
