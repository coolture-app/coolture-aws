# Scraper

Imports the events of the [trojmiasto.pl](https://www.trojmiasto.pl/imprezy/dni,30dni.html) 30-day listing as posts of a bot
account. It runs outside AWS and writes only through the public API, as `POST /posts`, like any other client. Aurora isn't
reachable from outside the VPC.

Each post has a real title and description, the event's photos, its address, and coordinates, which the feed's proximity signal needs. An event held
in a place is sent as `OFFLINE`. If it can't be located it's left out, because an `ONLINE` post would get no proximity signal.

## Running it

The `make` targets run it in the repository's `.venv`, which they create on first use. It locates events with the
local geocoder, the same Photon and index the app uses on AWS, so `make geo-index` must have run once and `make up`
started it. That holds when the run sends to AWS too.

```bash
make scrape-snapshot                                  # saves ~120 events of every category to scraper/snapshot, no API needed
make seed                                             # loads the snapshot into the local stack, see README.md "Run locally"

make local                                            # in another terminal
make scrape                                           # imports live as the local bot user
make scrape ARGS='--limit 20'
make scrape ARGS='--dry-run --limit 20'               # prints the posts as JSON lines, uploads and writes nothing
make test-scraper
```

## Snapshot and seed

A local stack gets its events from a snapshot, so nobody waits for a live scrape after every `make reset-db`.

- **`scrape.py --snapshot DIR`** (`make scrape-snapshot`) saves posts as `posts.jsonl` and their photos under `images/`,
  instead of sending them. The listing comes sorted by category, so the first 60 would all be concerts. Instead every
  top category gets a quota by power allocation, the square root of how many events it lists (Bankier, 1988), which
  gives big categories more without drowning the small ones: of 120, 24 concerts, 19 shows, 16 parties, 15 exhibitions,
  13 films, 12 sport, 8 occasional, 7 fairs and 6 food on 2026-11-09. Within a category, events are taken one per day
  before a second of any day, so they spread over the 30 days. `--all` saves the whole listing. A run adds to what's
  there and writes `complete` at the end, so one that stopped goes on where it was. A category with too few events
  ahead, such as exhibitions that already opened, is logged with what it got.
- **`seed.py events`** (`make seed-events`) waits for the API, then loads the posts not yet there as the bot, four at
  a time. Every date moves forward by the same whole number of days on the Warsaw clock, the fewest that put every
  start at least two hours ahead, so the snapshot's days keep their order and each event its hour.
- **`seed.py reactions`** (`make seed-reactions`) ranks the bot's posts by cosine distance between each post's
  embedding and the mean of a demo user's interest embeddings. The user likes the posts ranked 4, 7, 10, 13 and 16,
  marks 5 and 11 as interesting, and dislikes the two farthest. The closest three stay unreacted, because reacted posts
  leave the feed. A user who has any reaction or participation already is skipped, so a second run adds nothing.

The snapshot is gitignored, like the ledger, because scraped text and photos stay out of the public repository.

Most of a run is waiting: the site gets at most one request a second, and a local write took a median of 3.3 s. The
geocoder is ours, so it isn't paced. Later runs skip the events already imported, without opening their pages. Each post then reaches
`post-embedding` as usual. Locally that needs `make embed-posts`, because there are no S3 events.

A throttled request is tried 3 more times, waiting what the server's `Retry-After` asks, up to 2 minutes each. A failure to reach the site's listing,
the API or the geocoder then ends the run with one error, the totals and exit 1. What's already imported stays, and
the next run goes on from there. A post that timed out counts as `rejected`, because it may have been saved, and the next run's list settles it.

## On a schedule

On AWS the scraper runs by itself, as the Lambda `coolture-<env>-scraper` (`aws/scraper.yaml`, entry point
`lambda_handler.py`). EventBridge Scheduler starts it at a random moment in every 6 hours, so it never asks the site at
the same times, and the site's pages are a random 1 to 3 seconds apart. Each run imports what the app doesn't have yet,
by event URL, and stops in time for the Lambda's 15 minutes. The next run goes on from there, so the first runs catch up
on the whole listing over a few hours.

It runs outside the VPC, because it needs the site. So it calls API Gateway directly with the `X-Origin-Verify` secret
instead of going through CloudFront, signs in as the bot with Cognito's admin flow, asks the geocoder through a small
proxy Lambda inside the VPC, and keeps its ledger in S3 instead of a local file.

To turn it on, once `make deploy-scraper` has run (`<pool>` is the auth stack's `UserPoolId` output):

```bash
aws cognito-idp admin-create-user --user-pool-id <pool> --username <bot email> --message-action SUPPRESS \
    --user-attributes Name=email,Value=<bot email> Name=email_verified,Value=true
aws cognito-idp admin-set-user-password --user-pool-id <pool> --username <bot email> --password '<password>' --permanent
aws secretsmanager put-secret-value --secret-id coolture-dev-scraper-bot \
    --secret-string '{"username": "<bot email>", "password": "<password>"}'
SCRAPER_SCHEDULE=true SCRAPER_END_DATE=2027-06-30T00:00:00Z make deploy-scraper   # the end date is optional
make scrape-aws                                                                   # one run now, to check it
```

`SCRAPER_SCHEDULE=false make deploy-scraper` turns it off. The schedule stops by itself at `SCRAPER_END_DATE`.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SCRAPER_API_URL` | `http://localhost:3000` | The API. On AWS it ends in `/api` |
| `SCRAPER_USER_ID` | set by `make scrape` | Bot identity on the local stack, sent as `X-User-Id` with `X-User-Name: coolture_bot`. A bot user created earlier keeps its old name until `make reset-db` |
| `SCRAPER_API_TOKEN` | | Bot identity on AWS for a run from a machine: a Cognito id token, which lasts an hour |
| `SCRAPER_GEOCODER_URL` | `http://localhost:2322` | Photon, the local container by default |

## What it reads

| Value | Source |
|---|---|
| Title, category, city, venue, date, hours | The listing item. A combined city such as `Gdańsk Sopot` keeps its first Tricity name |
| Description | The longer of the page's `.text-contents` and the schema.org `Event` block, whose text stops at the first quotation mark. The page's text is laid out as plain paragraphs, see below |
| Street, postal code, online or not | The schema.org `Event` block. Theatre pages on `/kultura/` have none, so only the venue is known |
| Coordinates | Photon: the street address, then the venue, then the city centre, limited to Pomerania |
| Missing postal code | Photon's answer to the search. A place with no postal code is still sent |
| Photos | The page's `og:image` first, as the cover, then each `a.tinyGallery__item`'s `data-href`, the largest the site serves (1920 px wide when it has it, else `0x500`), never the `img` inside it. Only photos under `/zdj/`, each photo id once, and none from the lists of other events and articles on the page |

Run totals are logged at the end. `exact_location` and `city_location` show how many posts have a precise point, and `no_location`
how many were left out. `with_image` counts the posts created with at least one photo, and `photos` the photos they got.

Each photo is downloaded, its width and height read from its header, and uploaded through `/media/uploads` in that order.
A post's first photo is its cover. A photo that can't be downloaded or uploaded
is left out and the rest go on, up to the API's 8 photos a post. An upload that fails after `/media/uploads/init` deletes its
media, and so does a post the API refuses with a 4xx. A timeout or a 5xx keeps them, because the post may have been saved.
A 401 or 403 stops the run. `--dry-run` prints each photo it would upload under `images`, as `fileName`, `mimeType`,
`sizeBytes`, `width` and `height`.

The page's text becomes plain paragraphs, separated by a blank line:

- A heading gets its own paragraph: a line in bold on its own, a short line ending in `?` or `:`, or one in capitals.
- A line the site wrapped in the middle of a sentence is joined to the next. List items, which get a `•`, and dates stay on
  their own lines.
- Link labels such as "KUP BILET" or "Posłuchaj …" go, while a link holding a name, an address or an email stays.
- Lines at the start that repeat the post's own start or address ("Kiedy: …", "Gdzie: …", "Termin: …", "Miejsce: …") go.
  "Bilety: …" and "Dla kogo: …" stay, because a post has no field for them.
- A description over 1,000 characters is cut at the end of a sentence or a line.

## Limits

- **Events that have already started are skipped,** because `POST /posts` accepts only future starts. That includes exhibitions
  that opened earlier.
- **Imported posts aren't updated.** A later change on the site isn't picked up, and posts imported before photos have none.
- **An event with no hour starts at 12:00.**
- **A deleted post stays out only on the machine that saw it.** `GET /posts` never returns a deleted post, so the scraper keeps the URLs it has seen
  in `scraper/.imported-<api host>.txt`, which git ignores. That covers one operator per environment: a post deleted before this machine ever saw it
  can't be known. A database with none of the bot's posts counts as a new one, so the file starts over after `make reset-db`.
- **Tests use invented text, and imports stay local.** The site's terms don't settle whether its text can be reused, so don't commit scraped text, or
  import it anywhere people outside the team can see it, until we have the site's permission.
