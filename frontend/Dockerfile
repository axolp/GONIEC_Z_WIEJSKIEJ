FROM node:22-alpine
WORKDIR /app/frontend
COPY frontend/ ./
COPY data/seed-bills.json /app/data/seed-bills.json
COPY data/presidential-comments.json /app/data/presidential-comments.json
COPY data/bill-votings.json /app/data/bill-votings.json
ENV PORT=5173
EXPOSE 5173
CMD ["node", "server.mjs"]
