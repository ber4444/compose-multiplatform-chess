# Build context is a staging directory made by tools/deploy_web.sh: the production wasm dist plus
# web/Caddyfile. Nothing is compiled here; Gradle has already produced the bundle.
FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY site/ /srv/
EXPOSE 8080
