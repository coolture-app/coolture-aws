# AuraDB check

For: anyone building the graph gateway.

Code in the Lambda image can reach AuraDB and run PageRank.
This ran on a laptop, not on AWS.

| Check | Result |
| --- | --- |
| Read and write | worked in 3 of 3 runs |
| First connection | 1.9 to 2.8 s |
| Open an analytics session | 35 to 39 s |
| PageRank on 5 nodes | 2.0 to 2.4 s |
| Whole run | 86 to 94 s |
| Peak memory | 197 MB |
| Second session while one is open | opened in 3 of 3 runs |

Sessions need the database login and an Aura API key.

## Not known yet

- Whether a real Lambda on AWS can reach AuraDB.
- How PageRank behaves on the full seeded graph.
- Whether sessions are free, which needs the Aura console.
- Why a second session opened, since the Aura docs give a limit of one.
