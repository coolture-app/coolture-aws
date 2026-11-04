# Scraper

Imports the events of the [trojmiasto.pl](https://www.trojmiasto.pl/imprezy/dni,30dni.html) 30-day listing as posts of a bot
account. It runs outside AWS and writes only through the public API, as `POST /posts`, like any other client. Aurora isn't
reachable from outside the VPC.

Each post has a real title and description, its address, and coordinates, which the feed's proximity signal needs. An event held
in a place is sent as `OFFLINE`. If it can't be located it's left out, because an `ONLINE` post would get no proximity signal.

## Running it

```bash
python3 -m venv scraper/.venv                         # python on Windows
source scraper/.venv/bin/activate                     # source scraper/.venv/Scripts/activate on Windows
python -m pip install -r scraper/requirements-dev.txt

python scraper/scrape.py --dry-run --limit 20         # prints the posts as JSON lines, writes nothing

make local                                            # in another terminal
make scrape                                           # imports as the local bot user
make scrape ARGS='--limit 20'
make test-scraper
```

We estimate a whole import of about 400 events at about 40 minutes locally: the site and Nominatim each get at most one request a second, and a
local write took a median of 3.3 s. Later runs skip the events already imported, without opening their pages. Each post then reaches
`post-embedding` as usual. Locally that needs `make embed-posts`, because there are no S3 events.

A throttled request is tried 3 more times, waiting what the server's `Retry-After` asks, up to 2 minutes each. A failure to reach the site's listing,
the API or Nominatim then ends the run with one error, the totals and exit 1. What's already imported stays, and
the next run goes on from there. A post that timed out counts as `rejected`, because it may have been saved, and the next run's list settles it.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SCRAPER_API_URL` | `http://localhost:3000` | The API. On AWS it ends in `/api` |
| `SCRAPER_USER_ID` | set by `make scrape` | Bot identity on the local stack, sent as `X-User-Id` with `X-User-Name: coolture_bot`. A bot user created earlier keeps its old name until `make reset-db` |
| `SCRAPER_API_TOKEN` | | Bot identity on AWS: a Cognito id token, which lasts an hour |
| `SCRAPER_NOMINATIM_URL` | `https://nominatim.openstreetmap.org` | Geocoder |

## What it reads

| Value | Source |
|---|---|
| Title, category, city, venue, date, hours | The listing item. A combined city such as `Gdańsk Sopot` keeps its first Tricity name |
| Description | The longer of the page's `.text-contents`, with one line per paragraph and without the site's links and footer, and the schema.org `Event` block, whose text stops at the first quotation mark |
| Street, postal code, online or not | The schema.org `Event` block. Theatre pages on `/kultura/` have none, so only the venue is known |
| Coordinates | Nominatim: the street address, then the venue, then the city centre, limited to Pomerania |
| Missing postal code | Nominatim's reverse lookup at those coordinates, only when neither the page nor the search has one |

Run totals are logged at the end. `exact_location` and `city_location` show how many posts have a precise point, and `no_location`
how many were left out.

## Limits

- **Events that have already started are skipped,** because `POST /posts` accepts only future starts. That includes exhibitions
  that opened earlier.
- **Imported posts aren't updated.** A later change on the site isn't picked up.
- **An event with no hour starts at 12:00.**
- **A deleted post stays out only on the machine that saw it.** `GET /posts` never returns a deleted post, so the scraper keeps the URLs it has seen
  in `scraper/.imported-<api host>.txt`, which git ignores. That covers one operator per environment: a post deleted before this machine ever saw it
  can't be known. A database with none of the bot's posts counts as a new one, so the file starts over after `make reset-db`.
- **Tests use invented text, and imports stay local.** The site's terms don't settle whether its text can be reused, so don't commit scraped text, or
  import it anywhere people outside the team can see it, until we have the site's permission.
