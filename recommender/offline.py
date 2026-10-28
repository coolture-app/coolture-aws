import argparse
import os
from pathlib import Path

import psycopg

from catalogue import publish_catalogue, read_catalogue
from embedding import MODELS, STORAGE_DIMENSION, SentenceTransformerEmbedder, embed_interests


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('publish-catalogue').add_argument('path', type=Path)
    embed = commands.add_parser('embed-interests')
    embed.add_argument('model', choices=sorted(MODELS))
    embed.add_argument('--dimension', type=int, default=STORAGE_DIMENSION)
    args = parser.parse_args()
    with psycopg.connect(os.environ['DATABASE_URL']) as conn:
        if args.command == 'publish-catalogue':
            catalogue, sha256 = read_catalogue(args.path)
            is_new = publish_catalogue(conn, catalogue, sha256)
            print(f'level=info event=catalogue_published version={catalogue.version} new={is_new}')
        else:
            embedder = SentenceTransformerEmbedder(MODELS[args.model], args.dimension)
            count = embed_interests(conn, embedder)
            print(f'level=info event=interests_embedded model_key={embedder.model_key} count={count}')


if __name__ == '__main__':
    main()
