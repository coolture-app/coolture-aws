# Scraper

Imports the events of the [trojmiasto.pl](https://www.trojmiasto.pl/imprezy/dni,30dni.html) 30-day listing as posts of a bot
account. It runs outside AWS and writes only through the public API, as `POST /posts`, like any other client. Aurora isn't
reachable from outside the VPC.

Each post has a real title and description, its address, and coordinates, which the feed's proximity signal needs. An event held
in a place is sent as `OFFLINE`. If it can't be located it's left out, because an `ONLINE` post would get no proximity signal.

## Running it

```bash
python3 -m venv scraper/.venv && scraper/.venv/bin/pip install -r scraper/requirements-dev.txt
source scraper/.venv/bin/activate

python3 scraper/scrape.py --dry-run --limit 20        # prints the posts as JSON lines, writes nothing

make local                                            # in another terminal
make scrape                                           # imports as the local bot user
make scrape ARGS='--limit 20'
make test-scraper
```

A whole import of about 400 events takes about 25 minutes, because the site and Nominatim each get at most one request a second. Later runs skip the
events already imported, without opening their pages. Each post then reaches `post-embedding` as usual. Locally that needs
`make embed-posts`, because there are no S3 events.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SCRAPER_API_URL` | `http://localhost:3000` | The API. On AWS it ends in `/api` |
| `SCRAPER_USER_ID` | set by `make scrape` | Bot identity on the local stack, sent as `X-User-Id` |
| `SCRAPER_API_TOKEN` | | Bot identity on AWS: a Cognito id token, which lasts an hour |
| `SCRAPER_NOMINATIM_URL` | `https://nominatim.openstreetmap.org` | Geocoder |

## What it reads

| Value | Source |
|---|---|
| Title, category, city, venue, date, hour | The listing item |
| Description | The page's `.text-contents` with one line per paragraph, without the site's links and footer, else the schema.org `Event` block, whose text stops at the first quotation mark |
| Street, postal code, online or not | The schema.org `Event` block. Theatre pages on `/kultura/` have none, so only the venue is known |
| Coordinates | Nominatim: the street address, then the venue, then the city centre, limited to Pomerania |
| Missing postal code | Nominatim's reverse lookup at those coordinates |

Run totals are logged at the end. `exact_location` and `city_location` show how many posts have a precise point, and `no_location`
how many were left out.

## Limits

- **Events that have already started are skipped,** because `POST /posts` accepts only future starts. That includes exhibitions
  that opened earlier.
- **Imported posts aren't updated.** A later change on the site isn't picked up.
- **An event with no hour starts at 12:00.**
- **Tests use invented text.** Don't commit real scraped text until we've checked the site's terms.
