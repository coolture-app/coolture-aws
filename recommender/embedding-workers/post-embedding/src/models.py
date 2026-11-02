from typing import Optional
from uuid import UUID

from pydantic import BaseModel


class PostContent(BaseModel):
    title: str
    description: Optional[str] = None
    tags: Optional[list[str]] = None

    @property
    def text(self) -> str:
        tags_str = " ".join(self.tags or [])
        return f"{self.title} {self.description or ''} {tags_str}".strip()


class PostEmbeddingRecord(BaseModel):
    post_id: UUID
    embedding: list[float]
    model: str
