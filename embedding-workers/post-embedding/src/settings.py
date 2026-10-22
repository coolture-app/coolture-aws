from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    db_host: str = "host.docker.internal"
    db_port: int = 5430
    db_name: str = "coolture"
    db_username: str
    db_password: str

    embedding_model: str = "all-mpnet-base-v2"
    embedding_model_dir: str = "/opt/model"


settings = Settings()
