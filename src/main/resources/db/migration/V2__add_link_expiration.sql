ALTER TABLE public.links
    ADD COLUMN expires_at timestamp(6) with time zone,
    ADD CONSTRAINT ck_links_expiry_after_creation
        CHECK (expires_at IS NULL OR expires_at > created_at);
