CREATE TABLE public.links (
    id uuid CONSTRAINT pk_links PRIMARY KEY,
    code varchar(8) COLLATE "C" NOT NULL,
    destination_url varchar(2048) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    disabled_at timestamp(6) with time zone,
    CONSTRAINT uq_links_code UNIQUE (code),
    CONSTRAINT ck_links_code_base62 CHECK (code ~ '^[A-Za-z0-9]{8}$'),
    CONSTRAINT ck_links_destination_length CHECK (char_length(destination_url) > 0)
);

CREATE TABLE public.idempotency_records (
    key_digest char(64) CONSTRAINT pk_idempotency_records PRIMARY KEY,
    request_fingerprint char(64) NOT NULL,
    link_id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ck_idempotency_key_digest_hex CHECK (key_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_fingerprint_hex CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_idempotency_link FOREIGN KEY (link_id) REFERENCES public.links(id)
);

CREATE INDEX idx_idempotency_created_at ON public.idempotency_records(created_at);
GRANT SELECT, INSERT, UPDATE ON public.links TO urlshortener_app;
GRANT SELECT, INSERT, DELETE ON public.idempotency_records TO urlshortener_app;
