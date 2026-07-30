# syntax=docker/dockerfile:1
FROM --platform=linux/amd64 flyio/postgres-flex:16.3

# Install the pgvector extension for Postgres 16
RUN <<EOF
apt-get update -y
apt-get install -y postgresql-16-pgvector
apt-get clean -y
EOF
