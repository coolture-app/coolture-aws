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


settings = Settings()
