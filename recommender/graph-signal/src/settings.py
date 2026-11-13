from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # sam local passes the template's stack imports as empty strings.
    model_config = SettingsConfigDict(extra="ignore", env_ignore_empty=True)

    db_host: str = "host.docker.internal"
    db_port: int = 5430
    db_name: str = "coolture"
    db_username: str
    db_password: str = ""
    db_iam_auth: bool = False
    db_connect_timeout_seconds: int = 5
    aws_region: str = "eu-north-1"

    # The feed's FeedConfig.EMBEDDING_MODEL, STANCE_WEIGHTS, STANCE_HALF_LIFE_DAYS, STANCE_MAX_AGE_DAYS and
    # DEFAULT_EVENT_HOURS. tests/test_feed_constants.py checks they agree.
    embedding_model: str = "sdadas/mmlw-roberta-base"
    weight_like: float = 1.0
    weight_interested: float = 1.5
    weight_takes_part: float = 2.0
    stance_half_life_days: float = 14
    stance_max_age_days: int = 112
    default_event_hours: int = 3


settings = Settings()
