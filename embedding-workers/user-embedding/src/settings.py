from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    db_host: str = "host.docker.internal"
    db_port: int = 5430
    db_name: str = "coolture"
    db_username: str
    db_password: str

    embedding_model: str = "all-mpnet-base-v2"

    weight_like: float = 1.0
    weight_dislike: float = -0.5
    weight_interested: float = 1.5
    weight_takes_part: float = 2.0


settings = Settings()
