import os
from dataclasses import asdict
from uuid import UUID

import psycopg
from fastapi import FastAPI
from fastapi.responses import JSONResponse

from catalogue import list_active_interests
from selection import (EmbeddingsNotReady, ProfileChanged, SelectionRejected, SelectionRequest, UserUnknown,
                       replace_selection)


def create_app(database_url: str, model_key: str) -> FastAPI:
    app = FastAPI(title='Coolture recommender')

    @app.get('/v1/interests')
    def get_interests():
        with psycopg.connect(database_url) as conn:
            version, interests = list_active_interests(conn)
        return {'catalogueVersion': version, 'interests': interests}

    @app.put('/v1/users/{user_id}/interests')
    def put_interests(user_id: UUID, body: SelectionRequest):
        try:
            with psycopg.connect(database_url) as conn:
                profile = replace_selection(conn, user_id, body.interests, model_key)
        except UserUnknown:
            return JSONResponse({'error': 'user_unknown'}, 404)
        except ProfileChanged:
            return JSONResponse({'error': 'profile_changed'}, 409)
        except SelectionRejected as rejected:
            return JSONResponse({'error': 'selection_rejected', **asdict(rejected.problems)}, 422)
        except EmbeddingsNotReady as missing:
            return JSONResponse({'error': 'embeddings_not_ready', 'modelKey': missing.model_key,
                                 'interests': missing.keys}, 503)
        return {'userId': str(profile.user_id), 'revision': profile.revision, 'changed': profile.changed,
                'interests': [c.model_dump() for c in profile.choices]}

    return app


def app_from_environment() -> FastAPI:
    return create_app(os.environ['DATABASE_URL'], os.environ['EMBEDDING_MODEL_KEY'])
