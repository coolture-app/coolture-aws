package com.coolture.repository;

/** The author already has a post from this event page, which a retried or parallel scraper run would add again. */
public class DuplicateEventException extends RuntimeException {
    public DuplicateEventException(Throwable cause) {
        super("This author already has a post for that eventUrl", cause);
    }
}
