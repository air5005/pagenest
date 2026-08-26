# PageNest Resilient Open Catalog Design

## Goal

Make Online Discovery useful on the target HyperOS device even when one public
catalog is slow or unreachable, while showing only lawful open-book links and
keeping the implementation ready for a PageNest-hosted catalog endpoint.

## Observed failure

The 1.14 device run waited for the source timeout and then rendered zero books.
The repository reduced every source exception to an identifier, so diagnostics
could not distinguish DNS/network failure, timeout, HTTP rejection, malformed
content, or an untrusted redirect. The public Gutendex deployment is also a test
service; its own project recommends self-hosting.

## Source roles

- Project Gutenberg remains the authoritative full-text public-domain source.
- Gutendex remains its JSON adapter. Its base URL is injectable so a future
  PageNest deployment can replace the public test endpoint without changing the
  catalog domain model.
- Open Library is added as a low-volume discovery fallback for public/full-text
  records and metadata. It must send a PageNest User-Agent, request only bounded
  fields, respect rate limits, and never present borrow-only items as free books.
- Wikimedia/Wikisource is reserved for a later Chinese-content phase after its
  export and attribution path is specified and tested.

## Reliability behavior

1. Each source is isolated by its own timeout.
2. A successful source remains visible when another source fails.
3. Failures retain a safe category (timeout, network, HTTP, malformed, size, or
   trust policy) for the app diagnostics screen; response bodies and URLs are not
   logged.
4. A stale cached catalog is returned when all live sources fail.
5. Empty successful responses are not allowed to erase a useful cache.
6. The shared client does not automatically follow redirects. A source may add
   redirect support only with an explicit HTTPS host allow-list; download URLs
   continue to pass the existing allow-list and private-address checks.

## Open Library fallback

The adapter uses `/search.json` for search, popular, latest, and recommended
queries. Only records marked `ebook_access=public` and `public_scan_b=true` are
included. Results expose their Open Library/Internet Archive page as an external
reading action; they are not silently downloaded as EPUB because an Archive
identifier does not guarantee a stable EPUB filename. Gutenberg acquisitions
continue to provide direct EPUB import when available.

## Testing

- Contract tests verify request fields, language/search/sort mapping, User-Agent,
  public-access filtering, and safe external links.
- Repository tests verify classified failures and stale-cache behavior.
- Registry/DI tests verify the stable source order and trusted redirect policy.
- The release gate includes focused JVM tests, the app test suite partitions,
  lint, APK build, installation on the connected HyperOS device, and a live
  Online Discovery check without accessing personal phone files.
