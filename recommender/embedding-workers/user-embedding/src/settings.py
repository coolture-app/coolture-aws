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

    embedding_model: str = "sdadas/mmlw-roberta-base"

    weight_like: float = 1.0
    weight_dislike: float = -0.5
    weight_interested: float = 1.5
    weight_takes_part: float = 2.0

    interest_weight_category: float = 0.25
    interest_weight_minimum: float = 0.2
    interest_decay_scale: float = 10.0
    engagement_window_days: int = 30


settings = Settings()
