CREATE TABLE redirect_events (
    id UUID CONSTRAINT pk_redirect_events PRIMARY KEY,
    link_id UUID NOT NULL CONSTRAINT fk_redirect_event_link REFERENCES links(id),
    occurred_at TIMESTAMPTZ(6) NOT NULL
);
CREATE INDEX idx_redirect_events_link_time ON redirect_events(link_id, occurred_at);
CREATE INDEX idx_redirect_events_time ON redirect_events(occurred_at);
GRANT SELECT, INSERT, DELETE ON redirect_events TO urlshortener_app;
