FROM alpine:latest
WORKDIR /app
COPY proxy-bin .
COPY certs/ ./certs/
# Install libc compatibility for go binary if not built statically (CGO_ENABLED=0 is safer)
RUN apk add --no-cache libc6-compat
CMD ["./proxy-bin"]
