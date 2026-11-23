from typing import Optional

from pydantic import BaseModel


class PostContent(BaseModel):
    title: str
    description: Optional[str] = None
    venue_name: Optional[str] = None
    city: Optional[str] = None

    @property
    def text(self) -> str:
        # The venue often names the kind of event ("Filharmonia", "Galeria") when the words don't. It goes last:
        # right after the title it pulls posts toward others at the same venue more than toward what a user liked.
        place = ", ".join(part for part in (self.venue_name, self.city) if part)
        return " ".join(part for part in (self.title, self.description, place) if part).strip()
