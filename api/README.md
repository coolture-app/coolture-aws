# API flows

Runnable examples of how the API is used, in [Hurl](https://hurl.dev): plain-text requests with the values they capture and
the answers they expect. They show what a contract can't: who may do what, and requests that depend on each other.
Shapes and parameters are in the contracts (`aws/lambdas/<area>/contracts/`, browsable with `make api-docs`).

| File | Shows |
|---|---|
| `identity.hurl` | Who the local API thinks you are, from the headers: `local_dev`, a seed user, a guest |
| `posts.hurl` | A post's life: create, read, edit (only the author), react, comment, delete |
| `media.hurl` | Uploading a photo to storage and making it an avatar |
| `users.hurl` | Searching people, profiles, following, interests |
| `feed.hurl` | The ranked feed and its paging, the full list with filters, the map |

## Running them

Start the stack and seed it (`make dev`, then `make seed`, see the root README), then:

```bash
make api-test                                                          # all of them, as a smoke test
hurl --variables-file api/local.env --very-verbose api/posts.hurl     # one, showing every request and answer
```

`local.env` holds the API's address and the seed users' ids. Each flow puts back what it changed, so they can run
any number of times. Posts it deletes stay in the database as soft-deleted rows, which nobody sees.

## Adding one

Write requests as a user would make them, with the seed users from `local.env` and the headers from
[Who you are](../README.md#who-you-are). Capture ids from answers (`[Captures]`), assert only what the flow is about
(`[Asserts]`), and undo every change at the end. A file reads top to bottom as a story, with a comment above each step.
