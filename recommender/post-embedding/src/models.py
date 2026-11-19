from typing import Optional
from uuid import UUID

from pydantic import BaseModel


class PostContent(BaseModel):
    title: str
    description: Optional[str] = None

    @property
    def text(self) -> str:
        return f"{self.title} {self.description or ''}".strip()


class PostEmbeddingRecord(BaseModel):
    post_id: UUID
    embedding: list[float]
    model: str
