from typing import Optional
from uuid import UUID

from pydantic import BaseModel


class PostContent(BaseModel):
    title: str
    description: Optional[str] = None
    tags: Optional[list[str]] = None

    @property
    def text(self) -> str:
        tags = f"Tagi: {', '.join(self.tags)}" if self.tags else None
        return "\n".join(part for part in (self.title, self.description, tags) if part)


class PostEmbeddingRecord(BaseModel):
    post_id: UUID
    embedding: list[float]
    model: str
