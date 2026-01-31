# RAG Q&A Bot Design

## Overview

A Q&A support bot using Retrieval Augmented Generation (RAG) that answers questions based on crawled FastAPI documentation.

## Tech Stack

- **Web Crawling**: requests + BeautifulSoup
- **Embeddings**: OpenAI text-embedding-3-small
- **Vector Database**: ChromaDB (persistent, file-based)
- **LLM**: Anthropic Claude (claude-3-5-sonnet)
- **API Framework**: FastAPI

## Project Structure

```
QABOT/
├── src/
│   ├── __init__.py
│   ├── crawler.py        # Web crawler for FastAPI docs
│   ├── chunker.py        # Text cleaning and chunking
│   ├── embeddings.py     # OpenAI embedding generation
│   ├── vectorstore.py    # ChromaDB operations
│   └── api.py            # FastAPI endpoints
├── data/
│   └── chroma_db/        # ChromaDB persistent storage
├── scripts/
│   └── ingest.py         # Pipeline: crawl → chunk → embed → store
├── .env.example          # Template for API keys
├── requirements.txt
└── README.md
```

## Components

### 1. Crawler (crawler.py)

- Start URL: https://fastapi.tiangolo.com/
- Breadth-first crawl within domain
- Limit: 30 pages
- Extract main content, skip nav/footer/sidebar
- 1-second delay between requests
- Returns: list of {url, title, content}

### 2. Chunker (chunker.py)

- Clean text (normalize whitespace, remove artifacts)
- Split into ~500 token chunks with ~50 token overlap
- Use tiktoken for accurate token counting
- Preserve metadata: source_url, title, chunk_index

### 3. Embeddings (embeddings.py)

- Model: text-embedding-3-small (1536 dimensions)
- Batch processing (100 chunks per request)
- Exponential backoff for rate limits

### 4. Vector Store (vectorstore.py)

- ChromaDB with persistent storage
- Collection: fastapi_docs
- Store: vector, text, metadata
- Search: cosine similarity, top-k retrieval

### 5. API Endpoints (api.py)

#### GET /health
- Response: {"status": "ok", "documents_count": N}

#### POST /search
- Request: {"query": "...", "k": 5}
- Response: {"results": [{"text": "...", "url": "...", "score": 0.87}, ...]}
- Pure retrieval without LLM

#### POST /ask
- Request: {"question": "..."}
- Response: {"answer": "...", "sources": ["url1", "url2"]}
- Full RAG: retrieve → prompt → Claude → answer

## Answer Generation

Prompt template:
```
You are a helpful assistant answering questions about FastAPI.
Use ONLY the following context to answer. If the context doesn't
contain enough information, say "I don't have enough information
to answer this question."

Context:
---
{retrieved_chunks}
---

Question: {user_question}

Provide a clear, concise answer and cite which sources you used.
```

Claude settings:
- Model: claude-3-5-sonnet
- Max tokens: 1024
- Temperature: 0.3

## Dependencies

```
fastapi>=0.109.0
uvicorn>=0.27.0
requests>=2.31.0
beautifulsoup4>=4.12.0
tiktoken>=0.5.0
openai>=1.10.0
anthropic>=0.18.0
chromadb>=0.4.22
python-dotenv>=1.0.0
```

## Usage

1. Copy `.env.example` to `.env` and add API keys
2. Install: `pip install -r requirements.txt`
3. Ingest: `python scripts/ingest.py`
4. Run: `uvicorn src.api:app --reload`
5. Test: `curl -X POST localhost:8000/ask -H "Content-Type: application/json" -d '{"question": "What is FastAPI?"}'`
