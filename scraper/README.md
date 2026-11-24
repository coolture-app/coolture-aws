# Scraper

Imports the events of the next 30 days on [trojmiasto.pl](https://www.trojmiasto.pl/imprezy/dni,30dni.html) as posts of a bot
account. It reads one listing per day (`/imprezy/dzien,2026-11-28`), because the 30-day listing stops at 400 events, and
an event on several days comes once. It writes only through the public API, as `POST /posts`, like any other client, because Aurora isn't
reachable from outside the VPC. It runs from a developer's machine, or on a timer on an always-on computer that imports into AWS (see below).

Each post has a real title and description, the event's photos, its address, and coordinates, which the feed's proximity signal needs. An event held
in a place is sent as `OFFLINE`. If it can't be located it's left out, because an `ONLINE` post would get no proximity signal.

## Running it

The `make` targets run it in the repository's `.venv`, which they create on first use. It locates events with the
local geocoder, the same Photon and index the app uses on AWS, so `make geo-index` must have run once and `make up`
started it. That holds when the run sends to AWS too.

```bash
make scrape-snapshot                                  # saves every listed event to scraper/snapshot, no API needed
make seed                                             # the local data set; its first step loads a sample of the snapshot (SIZE=N, SIZE=all)

make local                                            # in another terminal
make scrape                                           # imports live as the local bot user
make scrape ARGS='--limit 20'
make scrape ARGS='--dry-run --limit 20'               # prints the posts as JSON lines, uploads and writes nothing
make test-python                                      # its tests, with the other Python projects'
```

## Snapshot and seed

A local stack gets its events from a snapshot, so nobody waits for a live scrape after every `make reset-db`.

- **`scrape.py --snapshot DIR`** (`make scrape-snapshot`) saves posts as `posts.jsonl` and their photos under `images/`,
  instead of sending them. It always saves the whole listing, and `seed.py` takes the sample. A run adds to what's
  there and writes `complete` at the end, so one that stopped goes on where it was.
- **`seed.py events`** (`make seed-events`) waits for the API, then loads the posts not yet there as the bot, four at
  a time. By default it loads a sample: each top category gets a quota by power allocation, the square root of how many
  events it lists (Bankier, 1988), which gives big categories more without drowning the small ones, and within a
  category events are taken one per day before a second of any day, so they spread over the days. A reset is quick; `SIZE=N` picks another size and `SIZE=all` the whole snapshot. The same size gives everyone the same
  posts, and a bigger one later only adds what's missing. Every date moves forward by the same whole number of days on the Warsaw clock, the fewest that put every
  start at least two hours ahead, so the snapshot's days keep their order and each event its hour.
- **`seed.py reactions`** (`make seed-reactions`) ranks the bot's posts by cosine distance between each post's
  embedding and the mean of a demo user's interest embeddings. The user likes the posts ranked 4, 7, 10, 13 and 16,
  marks 5 and 11 as interesting, and the two farthest as "Nie dla mnie". The closest three stay unreacted, because reacted posts
  leave the feed. A user who has any reaction or participation already is skipped, so a second run adds nothing.
  With `RESET=1` (`--reset`) it first takes back all of their reactions and stances, so after their picks change in
  `db/local-dev-seed.sql` their history follows them again; what was done as them in the app goes too.
- **`seed.py friends`** (`make seed-friends`) takes the soonest posts of the Upcoming sort that nobody else marked, and
  has the demo users' friends from `db/local-dev-seed.sql` mark them, one shape of a card's friends line per post: one
  going, one interested, one of each, two going, five going, three going and four interested, and one marked only by
  Bartek and Iza, which shows no line. Kasia, Tomek and Magda get a plain coloured photo, so faces show both photos and
  initials. Any other stance of these friends is taken back, so each run puts the cases on the posts soonest then.
  The cases' stances are dated 6 weeks back: every friend is a persona, and fresh stances on whatever is soonest
  would lead their own Dla Ciebie.

The snapshot is gitignored, like the ledger, because scraped text and photos stay out of the public repository.

Most of a run is waiting: the site gets at most one request a second, with one listing page per day and a page per
event. The geocoder is ours, so it isn't paced. Later runs skip the events already imported, without opening their pages. Each post then reaches
`post-embedding` as usual. Locally that needs `make embed-posts`, because there are no S3 events.

A throttled request is tried 3 more times, waiting what the server's `Retry-After` asks, up to 2 minutes each. A failure to reach the site's listing,
the API or the geocoder then ends the run with one error, the totals and exit 1. What's already imported stays, and
the next run goes on from there. A post that timed out counts as `rejected`, because it may have been saved, and the next run's list settles it.
The post's `eventUrl` is the event's page, and the API refuses a second post with the same one from the same author
with a `409`, so a retried post or two runs at once can't import an event twice. The app never shows `eventUrl`.

## On an always-on computer

trojmiasto.pl's Cloudflare answers every request from AWS's addresses with a challenge (`403`, `cf-mitigated:
challenge`), so the scraper can't run on AWS. A computer outside it runs `remote.py` on a systemd timer instead, at a
random moment in every 6 hours, with the site's pages a random 1 to 3 seconds apart. Each run imports what the app
doesn't have yet, by event URL, and stops after 50 minutes, as the bot's token lasts an hour. The
next run goes on from there, so the first ones catch up on the whole listing.

It calls API Gateway directly with the `X-Origin-Verify` secret, signs in as the bot with Cognito's admin flow, asks
the geocoder through a small proxy Lambda inside the VPC, and keeps its ledger in S3. It takes all of that from the
outputs of the scraper stack (`aws/scraper.yaml`), through the stack's runner role. Each run's log goes to its own
stream in `/coolture/<env>/scraper` too, so anyone can read it with `aws logs tail /coolture/dev/scraper`.

**On AWS, once** (`<pool>` is the auth stack's `UserPoolId` output, `<user>` the scraper stack's `RunnerUserName`):

```bash
make deploy-scraper
aws cognito-idp admin-create-user --user-pool-id <pool> --username <bot email> --message-action SUPPRESS \
    --user-attributes Name=email,Value=<bot email> Name=email_verified,Value=true
aws cognito-idp admin-set-user-password --user-pool-id <pool> --username <bot email> --password '<password>' --permanent
aws secretsmanager put-secret-value --secret-id coolture-dev-scraper-bot \
    --secret-string '{"username": "<bot email>", "password": "<password>"}'
aws iam create-access-key --user-name <user>     # for the computer; pass it on through a password manager
```

**On the computer** (Ubuntu with systemd, git and curl). First check that the site lets its address in. This must
print `HTTP/2 200` and no `cf-mitigated`:

```bash
curl -s -o /dev/null -D - -A 'coolture-scraper/1.0 (+https://github.com/coolture-app/coolture-aws)' \
    "https://www.trojmiasto.pl/imprezy/dzien,$(date -d tomorrow +%F)?strona=0" | grep -iE '^HTTP|cf-mitigated'
```

SigV4 refuses a clock more than 5 minutes off, so `timedatectl` must say it's synchronised. A laptop needs
`HandleLidSwitch=ignore` in `/etc/systemd/logind.conf`, or it sleeps when closed. Then, as an admin:

```bash
sudo useradd --system --create-home --home-dir /var/lib/coolture-scraper --shell /usr/sbin/nologin coolture
sudo -u coolture -H sh -c 'curl -fsS https://mise.run | sh &&
    git clone --quiet --depth 1 --filter=blob:none --sparse https://github.com/coolture-app/coolture-aws.git ~/coolture-aws &&
    cd ~/coolture-aws && git sparse-checkout set scraper &&
    ~/.local/bin/mise trust && ~/.local/bin/mise install python &&
    "$(~/.local/bin/mise where python)/bin/python3" -m venv ~/venv && mkdir -m 700 ~/.aws'
sudoedit -u coolture /var/lib/coolture-scraper/.aws/credentials   # the access key, as [coolture-scraper-user]
sudoedit -u coolture /var/lib/coolture-scraper/.aws/config        # the profile below
sudo chmod 600 /var/lib/coolture-scraper/.aws/credentials
cd /var/lib/coolture-scraper/coolture-aws/scraper/systemd
sudo cp coolture-scraper.service coolture-scraper.timer /etc/systemd/system/
sudo cp coolture-scraper.env /etc/coolture-scraper.env   # set the branch there
sudo systemctl daemon-reload
sudo systemctl start coolture-scraper            # one run now; journalctl -u coolture-scraper -f
sudo systemctl enable --now coolture-scraper.timer
```

```ini
[profile coolture-scraper]
role_arn = <the scraper stack's RunnerRoleArn>
source_profile = coolture-scraper-user
region = eu-north-1
```

The service checks out `SCRAPER_BRANCH` from `/etc/coolture-scraper.env` before each run, and installs the
requirements again when they change, so the computer needs no updates by hand, except the units: copy them again after
they change here. Only `scraper/` is checked out, and the service sees no other folder of the computer. `systemctl list-timers coolture-scraper` shows the next run;
`sudo systemctl disable --now coolture-scraper.timer` stops it, and deactivating the access key stops it from anywhere.

**From any computer, once:** `make scrape-aws`, with `AWS_PROFILE` set to a profile that assumes the runner role. The
team's developer role may assume it, so the profile's `source_profile` can be your own. The site must let your address
in, as above.

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
A post's first photo is its cover. An upload that times out or gets a 5xx is tried once more after 5 s. A photo that can't be
downloaded or uploaded is left out and the rest go on, up to the API's 8 photos a post. An upload that fails after `/media/uploads/init` deletes its
media, and so does a post the API refuses with a 4xx. A timeout or a 5xx keeps them, because the post may have been saved.
A 401 or 403 stops the run. `--dry-run` prints each photo it would upload under `images`, as `fileName`, `mimeType`,
`sizeBytes`, `width` and `height`.

The end is the first the site gives: the page's `endDate` (a last day runs to 23:59), then a run written in the text
("Wystawa czynna: od … do 28 lutego 2027", a day with no year being the next one after the start), then the listing's hour
range ("Godz. 14:00 - 18:00"). With none of them, a page saying "wystawa stała", "ekspozycja stała" or "do odwołania" makes a
post with no end date (`openEnded`); anything else keeps the API's 3-hour default. The "wystawy, spotkania" category alone
says nothing, since it also holds talks and workshops.

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
- **A deleted post stays out only where the ledger saw it.** `GET /posts` never returns a deleted post, so the scraper keeps the URLs it has seen: on a
  machine in `scraper/.imported-<api host>.txt`, which git ignores, for AWS in the scraper stack's S3 bucket. That covers one ledger per environment: a post deleted
  before it was seen can't be known. A database with none of the bot's posts counts as a new one, so the file starts over after `make reset-db`.
- **Tests use invented text, and imports stay in the team's own environments.** The site's terms don't settle whether its text can be reused, so don't commit scraped text, or
  import it anywhere people outside the team can see it, until we have the site's permission.
