# RAG Q&A Bot Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a Q&A bot that answers questions about FastAPI documentation using RAG.

**Architecture:** Crawl FastAPI docs → chunk text → generate embeddings → store in ChromaDB → serve via FastAPI with retrieval + LLM answer generation.

**Tech Stack:** Python, FastAPI, OpenAI (embeddings), Anthropic (LLM), ChromaDB, BeautifulSoup

---

## Task 1: Project Setup

**Files:**
- Create: `requirements.txt`
- Create: `.env.example`
- Create: `.gitignore`
- Create: `src/__init__.py`
- Create: `scripts/__init__.py`
- Create: `tests/__init__.py`

**Step 1: Create requirements.txt**

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
pytest>=8.0.0
pytest-asyncio>=0.23.0
httpx>=0.26.0
```

**Step 2: Create .env.example**

```
OPENAI_API_KEY=your_openai_key_here
ANTHROPIC_API_KEY=your_anthropic_key_here
```

**Step 3: Create .gitignore**

```
__pycache__/
*.py[cod]
.env
data/
.pytest_cache/
*.egg-info/
venv/
.venv/
```

**Step 4: Create directory structure and __init__.py files**

```bash
mkdir -p src scripts tests data
touch src/__init__.py scripts/__init__.py tests/__init__.py
```

**Step 5: Install dependencies**

Run: `pip install -r requirements.txt`

**Step 6: Commit**

```bash
git add requirements.txt .env.example .gitignore src/ scripts/ tests/
git commit -m "chore: initial project setup with dependencies"
```

---

## Task 2: Crawler Module

**Files:**
- Create: `src/crawler.py`
- Create: `tests/test_crawler.py`

**Step 1: Write crawler tests**

```python
# tests/test_crawler.py
import pytest
from unittest.mock import patch, Mock
from src.crawler import Crawler, Page


class TestCrawler:
    def test_extract_links_from_html(self):
        """Test that links are correctly extracted from HTML."""
        crawler = Crawler(base_url="https://fastapi.tiangolo.com", max_pages=30)
        html = '''
        <html>
        <body>
            <a href="/tutorial/">Tutorial</a>
            <a href="/advanced/">Advanced</a>
            <a href="https://github.com/tiangolo">External</a>
            <a href="#section">Anchor</a>
        </body>
        </html>
        '''
        links = crawler.extract_links(html, "https://fastapi.tiangolo.com/")

        assert "https://fastapi.tiangolo.com/tutorial/" in links
        assert "https://fastapi.tiangolo.com/advanced/" in links
        assert "https://github.com/tiangolo" not in links  # external
        assert "#section" not in links  # anchor

    def test_extract_content_from_html(self):
        """Test that main content is extracted, skipping nav/footer."""
        crawler = Crawler(base_url="https://fastapi.tiangolo.com", max_pages=30)
        html = '''
        <html>
        <head><title>FastAPI Tutorial</title></head>
        <body>
            <nav>Navigation content</nav>
            <main>
                <article>
                    <h1>Main Title</h1>
                    <p>This is the main content.</p>
                </article>
            </main>
            <footer>Footer content</footer>
        </body>
        </html>
        '''
        page = crawler.extract_content(html, "https://fastapi.tiangolo.com/tutorial/")

        assert page.title == "FastAPI Tutorial"
        assert "Main Title" in page.content
        assert "main content" in page.content
        assert "Navigation content" not in page.content
        assert "Footer content" not in page.content

    @patch('src.crawler.requests.get')
    def test_crawl_respects_max_pages(self, mock_get):
        """Test that crawler stops at max_pages limit."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.text = '''
        <html>
        <head><title>Page</title></head>
        <body><main><p>Content</p></main></body>
        </html>
        '''
        mock_get.return_value = mock_response

        crawler = Crawler(base_url="https://fastapi.tiangolo.com", max_pages=3)
        pages = crawler.crawl()

        assert len(pages) <= 3
```

**Step 2: Run tests to verify they fail**

Run: `pytest tests/test_crawler.py -v`
Expected: FAIL (module not found)

**Step 3: Implement crawler**

```python
# src/crawler.py
import time
from dataclasses import dataclass
from urllib.parse import urljoin, urlparse
from typing import List, Set

import requests
from bs4 import BeautifulSoup


@dataclass
class Page:
    url: str
    title: str
    content: str


class Crawler:
    def __init__(self, base_url: str, max_pages: int = 30, delay: float = 1.0):
        self.base_url = base_url
        self.max_pages = max_pages
        self.delay = delay
        self.visited: Set[str] = set()
        self.base_domain = urlparse(base_url).netloc

    def extract_links(self, html: str, current_url: str) -> List[str]:
        """Extract internal links from HTML."""
        soup = BeautifulSoup(html, 'html.parser')
        links = []

        for a_tag in soup.find_all('a', href=True):
            href = a_tag['href']

            # Skip anchors and empty links
            if not href or href.startswith('#'):
                continue

            # Convert relative URLs to absolute
            absolute_url = urljoin(current_url, href)
            parsed = urlparse(absolute_url)

            # Only keep internal links (same domain)
            if parsed.netloc == self.base_domain:
                # Normalize URL (remove fragment)
                clean_url = f"{parsed.scheme}://{parsed.netloc}{parsed.path}"
                if clean_url.endswith('/') or '.' not in parsed.path.split('/')[-1]:
                    if not clean_url.endswith('/'):
                        clean_url += '/'
                links.append(clean_url)

        return list(set(links))

    def extract_content(self, html: str, url: str) -> Page:
        """Extract main content from HTML, skipping nav/footer."""
        soup = BeautifulSoup(html, 'html.parser')

        # Get title
        title_tag = soup.find('title')
        title = title_tag.get_text(strip=True) if title_tag else url

        # Remove unwanted elements
        for element in soup.find_all(['nav', 'footer', 'header', 'script', 'style', 'aside']):
            element.decompose()

        # Try to find main content area
        main_content = soup.find('main') or soup.find('article') or soup.find('body')

        if main_content:
            # Get text, preserving some structure
            text = main_content.get_text(separator='\n', strip=True)
        else:
            text = soup.get_text(separator='\n', strip=True)

        # Clean up whitespace
        lines = [line.strip() for line in text.split('\n') if line.strip()]
        content = '\n'.join(lines)

        return Page(url=url, title=title, content=content)

    def fetch_page(self, url: str) -> str | None:
        """Fetch a single page with error handling."""
        try:
            response = requests.get(url, timeout=10)
            response.raise_for_status()
            return response.text
        except requests.RequestException as e:
            print(f"Error fetching {url}: {e}")
            return None

    def crawl(self) -> List[Page]:
        """Crawl the website starting from base_url."""
        pages: List[Page] = []
        queue: List[str] = [self.base_url]

        while queue and len(pages) < self.max_pages:
            url = queue.pop(0)

            if url in self.visited:
                continue

            self.visited.add(url)
            print(f"Crawling ({len(pages) + 1}/{self.max_pages}): {url}")

            html = self.fetch_page(url)
            if html is None:
                continue

            # Extract content
            page = self.extract_content(html, url)
            if page.content:  # Only add pages with content
                pages.append(page)

            # Extract and queue new links
            links = self.extract_links(html, url)
            for link in links:
                if link not in self.visited and link not in queue:
                    queue.append(link)

            # Be polite
            time.sleep(self.delay)

        print(f"Crawled {len(pages)} pages")
        return pages
```

**Step 4: Run tests to verify they pass**

Run: `pytest tests/test_crawler.py -v`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add src/crawler.py tests/test_crawler.py
git commit -m "feat: add web crawler for FastAPI docs"
```

---

## Task 3: Chunker Module

**Files:**
- Create: `src/chunker.py`
- Create: `tests/test_chunker.py`

**Step 1: Write chunker tests**

```python
# tests/test_chunker.py
import pytest
from src.chunker import Chunker, Chunk


class TestChunker:
    def test_clean_text(self):
        """Test text cleaning."""
        chunker = Chunker(chunk_size=500, overlap=50)
        dirty_text = "Hello   world\n\n\nThis   has  extra   spaces."
        clean = chunker.clean_text(dirty_text)

        assert "   " not in clean
        assert "\n\n\n" not in clean

    def test_chunk_short_text(self):
        """Short text should produce single chunk."""
        chunker = Chunker(chunk_size=500, overlap=50)
        chunks = chunker.chunk_text(
            text="This is a short text.",
            source_url="https://example.com",
            title="Test"
        )

        assert len(chunks) == 1
        assert chunks[0].text == "This is a short text."
        assert chunks[0].source_url == "https://example.com"
        assert chunks[0].chunk_index == 0

    def test_chunk_long_text_with_overlap(self):
        """Long text should be split with overlap."""
        chunker = Chunker(chunk_size=50, overlap=10)
        # Create text that will need multiple chunks
        text = " ".join(["word"] * 100)
        chunks = chunker.chunk_text(text, "https://example.com", "Test")

        assert len(chunks) > 1
        # Check chunk indices are sequential
        for i, chunk in enumerate(chunks):
            assert chunk.chunk_index == i

    def test_chunks_preserve_metadata(self):
        """Each chunk should have correct metadata."""
        chunker = Chunker(chunk_size=50, overlap=10)
        text = " ".join(["word"] * 100)
        chunks = chunker.chunk_text(text, "https://example.com/page", "Page Title")

        for chunk in chunks:
            assert chunk.source_url == "https://example.com/page"
            assert chunk.title == "Page Title"
```

**Step 2: Run tests to verify they fail**

Run: `pytest tests/test_chunker.py -v`
Expected: FAIL (module not found)

**Step 3: Implement chunker**

```python
# src/chunker.py
from dataclasses import dataclass
from typing import List
import re

import tiktoken


@dataclass
class Chunk:
    text: str
    source_url: str
    title: str
    chunk_index: int


class Chunker:
    def __init__(self, chunk_size: int = 500, overlap: int = 50):
        self.chunk_size = chunk_size
        self.overlap = overlap
        self.encoder = tiktoken.get_encoding("cl100k_base")

    def clean_text(self, text: str) -> str:
        """Clean text by normalizing whitespace."""
        # Replace multiple newlines with single newline
        text = re.sub(r'\n{3,}', '\n\n', text)
        # Replace multiple spaces with single space
        text = re.sub(r' {2,}', ' ', text)
        # Strip leading/trailing whitespace
        return text.strip()

    def count_tokens(self, text: str) -> int:
        """Count tokens in text using tiktoken."""
        return len(self.encoder.encode(text))

    def chunk_text(self, text: str, source_url: str, title: str) -> List[Chunk]:
        """Split text into chunks with overlap."""
        text = self.clean_text(text)

        # If text is short enough, return as single chunk
        if self.count_tokens(text) <= self.chunk_size:
            return [Chunk(text=text, source_url=source_url, title=title, chunk_index=0)]

        chunks: List[Chunk] = []

        # Split into sentences for better chunking
        sentences = re.split(r'(?<=[.!?])\s+', text)

        current_chunk: List[str] = []
        current_tokens = 0

        for sentence in sentences:
            sentence_tokens = self.count_tokens(sentence)

            # If single sentence is too long, split by words
            if sentence_tokens > self.chunk_size:
                # Save current chunk first
                if current_chunk:
                    chunk_text = ' '.join(current_chunk)
                    chunks.append(Chunk(
                        text=chunk_text,
                        source_url=source_url,
                        title=title,
                        chunk_index=len(chunks)
                    ))
                    current_chunk = []
                    current_tokens = 0

                # Split long sentence by words
                words = sentence.split()
                word_chunk: List[str] = []
                word_tokens = 0

                for word in words:
                    word_token_count = self.count_tokens(word + ' ')
                    if word_tokens + word_token_count > self.chunk_size and word_chunk:
                        chunks.append(Chunk(
                            text=' '.join(word_chunk),
                            source_url=source_url,
                            title=title,
                            chunk_index=len(chunks)
                        ))
                        # Keep overlap
                        overlap_words = word_chunk[-10:] if len(word_chunk) > 10 else []
                        word_chunk = overlap_words + [word]
                        word_tokens = self.count_tokens(' '.join(word_chunk))
                    else:
                        word_chunk.append(word)
                        word_tokens += word_token_count

                if word_chunk:
                    current_chunk = word_chunk
                    current_tokens = word_tokens
                continue

            # Check if adding this sentence exceeds chunk size
            if current_tokens + sentence_tokens > self.chunk_size and current_chunk:
                # Save current chunk
                chunk_text = ' '.join(current_chunk)
                chunks.append(Chunk(
                    text=chunk_text,
                    source_url=source_url,
                    title=title,
                    chunk_index=len(chunks)
                ))

                # Start new chunk with overlap from end of previous
                overlap_sentences = current_chunk[-2:] if len(current_chunk) > 2 else []
                current_chunk = overlap_sentences + [sentence]
                current_tokens = self.count_tokens(' '.join(current_chunk))
            else:
                current_chunk.append(sentence)
                current_tokens += sentence_tokens

        # Don't forget the last chunk
        if current_chunk:
            chunk_text = ' '.join(current_chunk)
            chunks.append(Chunk(
                text=chunk_text,
                source_url=source_url,
                title=title,
                chunk_index=len(chunks)
            ))

        return chunks
```

**Step 4: Run tests to verify they pass**

Run: `pytest tests/test_chunker.py -v`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add src/chunker.py tests/test_chunker.py
git commit -m "feat: add text chunker with token-based splitting"
```

---

## Task 4: Embeddings Module

**Files:**
- Create: `src/embeddings.py`
- Create: `tests/test_embeddings.py`

**Step 1: Write embeddings tests**

```python
# tests/test_embeddings.py
import pytest
from unittest.mock import patch, Mock
from src.embeddings import EmbeddingGenerator


class TestEmbeddingGenerator:
    @patch('src.embeddings.OpenAI')
    def test_generate_single_embedding(self, mock_openai_class):
        """Test generating embedding for single text."""
        # Setup mock
        mock_client = Mock()
        mock_openai_class.return_value = mock_client
        mock_response = Mock()
        mock_response.data = [Mock(embedding=[0.1] * 1536)]
        mock_client.embeddings.create.return_value = mock_response

        generator = EmbeddingGenerator()
        embeddings = generator.generate(["Hello world"])

        assert len(embeddings) == 1
        assert len(embeddings[0]) == 1536
        mock_client.embeddings.create.assert_called_once()

    @patch('src.embeddings.OpenAI')
    def test_generate_batch_embeddings(self, mock_openai_class):
        """Test generating embeddings for multiple texts."""
        mock_client = Mock()
        mock_openai_class.return_value = mock_client
        mock_response = Mock()
        mock_response.data = [Mock(embedding=[0.1] * 1536) for _ in range(3)]
        mock_client.embeddings.create.return_value = mock_response

        generator = EmbeddingGenerator()
        texts = ["Text 1", "Text 2", "Text 3"]
        embeddings = generator.generate(texts)

        assert len(embeddings) == 3

    @patch('src.embeddings.OpenAI')
    def test_empty_input_returns_empty_list(self, mock_openai_class):
        """Test that empty input returns empty list."""
        generator = EmbeddingGenerator()
        embeddings = generator.generate([])

        assert embeddings == []
```

**Step 2: Run tests to verify they fail**

Run: `pytest tests/test_embeddings.py -v`
Expected: FAIL (module not found)

**Step 3: Implement embeddings generator**

```python
# src/embeddings.py
import os
from typing import List
import time

from openai import OpenAI
from dotenv import load_dotenv

load_dotenv()


class EmbeddingGenerator:
    def __init__(self, model: str = "text-embedding-3-small", batch_size: int = 100):
        self.client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
        self.model = model
        self.batch_size = batch_size

    def generate(self, texts: List[str]) -> List[List[float]]:
        """Generate embeddings for a list of texts."""
        if not texts:
            return []

        all_embeddings: List[List[float]] = []

        # Process in batches
        for i in range(0, len(texts), self.batch_size):
            batch = texts[i:i + self.batch_size]

            try:
                response = self.client.embeddings.create(
                    model=self.model,
                    input=batch
                )

                batch_embeddings = [item.embedding for item in response.data]
                all_embeddings.extend(batch_embeddings)

                # Rate limit protection
                if i + self.batch_size < len(texts):
                    time.sleep(0.1)

            except Exception as e:
                print(f"Error generating embeddings for batch {i}: {e}")
                # Retry with exponential backoff
                for attempt in range(3):
                    time.sleep(2 ** attempt)
                    try:
                        response = self.client.embeddings.create(
                            model=self.model,
                            input=batch
                        )
                        batch_embeddings = [item.embedding for item in response.data]
                        all_embeddings.extend(batch_embeddings)
                        break
                    except Exception:
                        if attempt == 2:
                            raise

        return all_embeddings

    def generate_single(self, text: str) -> List[float]:
        """Generate embedding for a single text."""
        embeddings = self.generate([text])
        return embeddings[0] if embeddings else []
```

**Step 4: Run tests to verify they pass**

Run: `pytest tests/test_embeddings.py -v`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add src/embeddings.py tests/test_embeddings.py
git commit -m "feat: add OpenAI embedding generator with batching"
```

---

## Task 5: Vector Store Module

**Files:**
- Create: `src/vectorstore.py`
- Create: `tests/test_vectorstore.py`

**Step 1: Write vector store tests**

```python
# tests/test_vectorstore.py
import pytest
import tempfile
import shutil
from src.vectorstore import VectorStore


class TestVectorStore:
    @pytest.fixture
    def temp_db_path(self):
        """Create temporary directory for test database."""
        path = tempfile.mkdtemp()
        yield path
        shutil.rmtree(path)

    def test_add_and_search(self, temp_db_path):
        """Test adding documents and searching."""
        store = VectorStore(persist_path=temp_db_path)

        # Add documents
        texts = ["FastAPI is a web framework", "Python is a programming language"]
        embeddings = [[0.1] * 1536, [0.2] * 1536]
        metadatas = [
            {"source_url": "https://example.com/1", "title": "FastAPI"},
            {"source_url": "https://example.com/2", "title": "Python"}
        ]

        store.add(texts=texts, embeddings=embeddings, metadatas=metadatas)

        # Search
        results = store.search(query_embedding=[0.1] * 1536, k=1)

        assert len(results) == 1
        assert "FastAPI" in results[0]["text"]

    def test_count(self, temp_db_path):
        """Test document count."""
        store = VectorStore(persist_path=temp_db_path)

        assert store.count() == 0

        store.add(
            texts=["Test document"],
            embeddings=[[0.1] * 1536],
            metadatas=[{"source_url": "https://example.com", "title": "Test"}]
        )

        assert store.count() == 1

    def test_clear(self, temp_db_path):
        """Test clearing the store."""
        store = VectorStore(persist_path=temp_db_path)

        store.add(
            texts=["Test document"],
            embeddings=[[0.1] * 1536],
            metadatas=[{"source_url": "https://example.com", "title": "Test"}]
        )

        assert store.count() == 1
        store.clear()
        assert store.count() == 0
```

**Step 2: Run tests to verify they fail**

Run: `pytest tests/test_vectorstore.py -v`
Expected: FAIL (module not found)

**Step 3: Implement vector store**

```python
# src/vectorstore.py
from typing import List, Dict, Any
import chromadb
from chromadb.config import Settings


class VectorStore:
    def __init__(self, persist_path: str = "./data/chroma_db", collection_name: str = "fastapi_docs"):
        self.client = chromadb.PersistentClient(
            path=persist_path,
            settings=Settings(anonymized_telemetry=False)
        )
        self.collection = self.client.get_or_create_collection(
            name=collection_name,
            metadata={"hnsw:space": "cosine"}
        )

    def add(self, texts: List[str], embeddings: List[List[float]], metadatas: List[Dict[str, Any]]) -> None:
        """Add documents to the vector store."""
        if not texts:
            return

        # Generate unique IDs
        current_count = self.collection.count()
        ids = [f"doc_{current_count + i}" for i in range(len(texts))]

        self.collection.add(
            ids=ids,
            documents=texts,
            embeddings=embeddings,
            metadatas=metadatas
        )

    def search(self, query_embedding: List[float], k: int = 5) -> List[Dict[str, Any]]:
        """Search for similar documents."""
        results = self.collection.query(
            query_embeddings=[query_embedding],
            n_results=k,
            include=["documents", "metadatas", "distances"]
        )

        # Format results
        formatted_results = []
        if results['documents'] and results['documents'][0]:
            for i in range(len(results['documents'][0])):
                formatted_results.append({
                    "text": results['documents'][0][i],
                    "metadata": results['metadatas'][0][i] if results['metadatas'] else {},
                    "score": 1 - results['distances'][0][i] if results['distances'] else 0  # Convert distance to similarity
                })

        return formatted_results

    def count(self) -> int:
        """Return the number of documents in the store."""
        return self.collection.count()

    def clear(self) -> None:
        """Clear all documents from the store."""
        # Delete and recreate collection
        self.client.delete_collection(self.collection.name)
        self.collection = self.client.get_or_create_collection(
            name="fastapi_docs",
            metadata={"hnsw:space": "cosine"}
        )
```

**Step 4: Run tests to verify they pass**

Run: `pytest tests/test_vectorstore.py -v`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add src/vectorstore.py tests/test_vectorstore.py
git commit -m "feat: add ChromaDB vector store with search"
```

---

## Task 6: Ingest Script

**Files:**
- Create: `scripts/ingest.py`

**Step 1: Implement ingest script**

```python
# scripts/ingest.py
"""
Ingestion pipeline: crawl → chunk → embed → store
Run this once to populate the vector database.
"""
import sys
import os

# Add project root to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from src.crawler import Crawler
from src.chunker import Chunker, Chunk
from src.embeddings import EmbeddingGenerator
from src.vectorstore import VectorStore


def main():
    print("=" * 50)
    print("RAG Q&A Bot - Ingestion Pipeline")
    print("=" * 50)

    # Step 1: Crawl
    print("\n[1/4] Crawling FastAPI documentation...")
    crawler = Crawler(
        base_url="https://fastapi.tiangolo.com/",
        max_pages=30,
        delay=1.0
    )
    pages = crawler.crawl()
    print(f"Crawled {len(pages)} pages")

    # Step 2: Chunk
    print("\n[2/4] Chunking text...")
    chunker = Chunker(chunk_size=500, overlap=50)
    all_chunks: list[Chunk] = []

    for page in pages:
        chunks = chunker.chunk_text(
            text=page.content,
            source_url=page.url,
            title=page.title
        )
        all_chunks.extend(chunks)

    print(f"Created {len(all_chunks)} chunks")

    # Step 3: Generate embeddings
    print("\n[3/4] Generating embeddings...")
    generator = EmbeddingGenerator()
    texts = [chunk.text for chunk in all_chunks]
    embeddings = generator.generate(texts)
    print(f"Generated {len(embeddings)} embeddings")

    # Step 4: Store in vector database
    print("\n[4/4] Storing in vector database...")
    store = VectorStore()
    store.clear()  # Clear existing data

    metadatas = [
        {
            "source_url": chunk.source_url,
            "title": chunk.title,
            "chunk_index": chunk.chunk_index
        }
        for chunk in all_chunks
    ]

    store.add(texts=texts, embeddings=embeddings, metadatas=metadatas)
    print(f"Stored {store.count()} documents")

    print("\n" + "=" * 50)
    print("Ingestion complete!")
    print("=" * 50)


if __name__ == "__main__":
    main()
```

**Step 2: Test the script manually (requires API key)**

Run: `python scripts/ingest.py`
Expected: Script runs and shows progress

**Step 3: Commit**

```bash
git add scripts/ingest.py
git commit -m "feat: add ingestion pipeline script"
```

---

## Task 7: API Endpoints

**Files:**
- Create: `src/api.py`
- Create: `tests/test_api.py`

**Step 1: Write API tests**

```python
# tests/test_api.py
import pytest
from unittest.mock import patch, Mock
from fastapi.testclient import TestClient

# Mock the dependencies before importing api
with patch('src.api.VectorStore') as mock_store_class, \
     patch('src.api.EmbeddingGenerator') as mock_embed_class, \
     patch('src.api.Anthropic') as mock_anthropic_class:

    mock_store = Mock()
    mock_store.count.return_value = 100
    mock_store.search.return_value = [
        {"text": "FastAPI is a modern web framework", "metadata": {"source_url": "https://fastapi.tiangolo.com/"}, "score": 0.9}
    ]
    mock_store_class.return_value = mock_store

    mock_embed = Mock()
    mock_embed.generate_single.return_value = [0.1] * 1536
    mock_embed_class.return_value = mock_embed

    mock_client = Mock()
    mock_message = Mock()
    mock_message.content = [Mock(text="FastAPI is a web framework for building APIs.")]
    mock_client.messages.create.return_value = mock_message
    mock_anthropic_class.return_value = mock_client

    from src.api import app


@pytest.fixture
def client():
    return TestClient(app)


class TestHealthEndpoint:
    def test_health_returns_ok(self, client):
        response = client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "ok"
        assert "documents_count" in data


class TestSearchEndpoint:
    def test_search_returns_results(self, client):
        response = client.post("/search", json={"query": "what is fastapi", "k": 5})
        assert response.status_code == 200
        data = response.json()
        assert "results" in data

    def test_search_empty_query_fails(self, client):
        response = client.post("/search", json={"query": "", "k": 5})
        assert response.status_code == 400


class TestAskEndpoint:
    def test_ask_returns_answer(self, client):
        response = client.post("/ask", json={"question": "What is FastAPI?"})
        assert response.status_code == 200
        data = response.json()
        assert "answer" in data
        assert "sources" in data

    def test_ask_empty_question_fails(self, client):
        response = client.post("/ask", json={"question": ""})
        assert response.status_code == 400
```

**Step 2: Run tests to verify they fail**

Run: `pytest tests/test_api.py -v`
Expected: FAIL (module not found)

**Step 3: Implement API**

```python
# src/api.py
import os
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, field_validator
from anthropic import Anthropic
from dotenv import load_dotenv

from src.vectorstore import VectorStore
from src.embeddings import EmbeddingGenerator

load_dotenv()

app = FastAPI(
    title="RAG Q&A Bot",
    description="Q&A bot for FastAPI documentation using RAG",
    version="1.0.0"
)

# Initialize components
vector_store = VectorStore()
embedding_generator = EmbeddingGenerator()
anthropic_client = Anthropic(api_key=os.getenv("ANTHROPIC_API_KEY"))


# Request/Response models
class SearchRequest(BaseModel):
    query: str
    k: int = 5

    @field_validator('query')
    @classmethod
    def query_not_empty(cls, v):
        if not v or not v.strip():
            raise ValueError('Query cannot be empty')
        return v.strip()


class SearchResult(BaseModel):
    text: str
    url: str
    score: float


class SearchResponse(BaseModel):
    results: List[SearchResult]


class AskRequest(BaseModel):
    question: str

    @field_validator('question')
    @classmethod
    def question_not_empty(cls, v):
        if not v or not v.strip():
            raise ValueError('Question cannot be empty')
        return v.strip()


class AskResponse(BaseModel):
    answer: str
    sources: List[str]


class HealthResponse(BaseModel):
    status: str
    documents_count: int


# Endpoints
@app.get("/health", response_model=HealthResponse)
def health():
    """Health check endpoint."""
    return HealthResponse(
        status="ok",
        documents_count=vector_store.count()
    )


@app.post("/search", response_model=SearchResponse)
def search(request: SearchRequest):
    """Search for relevant documents without LLM generation."""
    try:
        # Generate embedding for query
        query_embedding = embedding_generator.generate_single(request.query)

        # Search vector store
        results = vector_store.search(query_embedding, k=request.k)

        # Format results
        search_results = [
            SearchResult(
                text=r["text"],
                url=r["metadata"].get("source_url", ""),
                score=r["score"]
            )
            for r in results
        ]

        return SearchResponse(results=search_results)

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Search failed: {str(e)}")


@app.post("/ask", response_model=AskResponse)
def ask(request: AskRequest):
    """Answer a question using RAG."""
    try:
        # Check if we have documents
        if vector_store.count() == 0:
            raise HTTPException(
                status_code=503,
                detail="Vector store is empty. Please run the ingestion script first."
            )

        # Generate embedding for question
        query_embedding = embedding_generator.generate_single(request.question)

        # Retrieve relevant chunks
        results = vector_store.search(query_embedding, k=5)

        if not results:
            return AskResponse(
                answer="I don't have enough information to answer this question.",
                sources=[]
            )

        # Build context from retrieved chunks
        context_parts = []
        sources = set()
        for r in results:
            context_parts.append(r["text"])
            if r["metadata"].get("source_url"):
                sources.add(r["metadata"]["source_url"])

        context = "\n\n---\n\n".join(context_parts)

        # Build prompt
        prompt = f"""You are a helpful assistant answering questions about FastAPI.
Use ONLY the following context to answer. If the context doesn't contain enough information, say "I don't have enough information to answer this question."

Context:
---
{context}
---

Question: {request.question}

Provide a clear, concise answer and cite which sources you used."""

        # Call Anthropic
        message = anthropic_client.messages.create(
            model="claude-3-5-sonnet-20241022",
            max_tokens=1024,
            messages=[
                {"role": "user", "content": prompt}
            ]
        )

        answer = message.content[0].text

        return AskResponse(
            answer=answer,
            sources=list(sources)
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to generate answer: {str(e)}")
```

**Step 4: Run tests to verify they pass**

Run: `pytest tests/test_api.py -v`
Expected: All tests PASS

**Step 5: Commit**

```bash
git add src/api.py tests/test_api.py
git commit -m "feat: add FastAPI endpoints for health, search, and ask"
```

---

## Task 8: Final Setup and README

**Files:**
- Create: `README.md`

**Step 1: Create README**

```markdown
# RAG Q&A Bot

A Q&A support bot that uses Retrieval Augmented Generation (RAG) to answer questions about FastAPI documentation.

## Features

- Web crawler for FastAPI documentation
- Text chunking with token-based splitting
- OpenAI embeddings for semantic search
- ChromaDB vector database for storage
- Anthropic Claude for answer generation
- FastAPI REST API

## Setup

1. Clone the repository
2. Create virtual environment:
   ```bash
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate
   ```
3. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
4. Create `.env` file with your API keys:
   ```bash
   cp .env.example .env
   # Edit .env and add your keys
   ```

## Usage

### 1. Ingest Data

Run the ingestion pipeline to crawl FastAPI docs and build the vector database:

```bash
python scripts/ingest.py
```

This will:
- Crawl ~30 pages from fastapi.tiangolo.com
- Chunk the text into ~500 token segments
- Generate embeddings using OpenAI
- Store everything in ChromaDB

### 2. Start the API

```bash
uvicorn src.api:app --reload
```

The API will be available at http://localhost:8000

### 3. API Endpoints

#### Health Check
```bash
curl http://localhost:8000/health
```

#### Search (retrieval only)
```bash
curl -X POST http://localhost:8000/search \
  -H "Content-Type: application/json" \
  -d '{"query": "path parameters", "k": 5}'
```

#### Ask (full RAG)
```bash
curl -X POST http://localhost:8000/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "How do I define path parameters in FastAPI?"}'
```

## Project Structure

```
QABOT/
├── src/
│   ├── crawler.py      # Web crawler
│   ├── chunker.py      # Text chunking
│   ├── embeddings.py   # OpenAI embeddings
│   ├── vectorstore.py  # ChromaDB operations
│   └── api.py          # FastAPI endpoints
├── scripts/
│   └── ingest.py       # Ingestion pipeline
├── tests/              # Test files
├── data/               # ChromaDB storage (gitignored)
└── docs/plans/         # Design documents
```

## Running Tests

```bash
pytest tests/ -v
```
```

**Step 2: Run all tests**

Run: `pytest tests/ -v`
Expected: All tests PASS

**Step 3: Final commit**

```bash
git add README.md
git commit -m "docs: add README with setup and usage instructions"
```

---

## Task 9: Create PR

**Step 1: Create feature branch and push**

Since we're already on master and have commits, we need to create a branch for the PR:

```bash
git checkout -b feature/rag-qa-bot
git push -u origin feature/rag-qa-bot
```

**Step 2: Create Pull Request**

```bash
gh pr create --title "Add RAG Q&A Bot for FastAPI documentation" --body "$(cat <<'EOF'
## Summary
- Web crawler for FastAPI documentation (30 pages limit)
- Text chunker with token-based splitting and overlap
- OpenAI embeddings integration
- ChromaDB vector store for persistence
- FastAPI API with /health, /search, and /ask endpoints
- Anthropic Claude integration for answer generation

## Test plan
- [ ] Run `pytest tests/ -v` to verify all tests pass
- [ ] Set up `.env` with API keys
- [ ] Run `python scripts/ingest.py` to ingest data
- [ ] Run `uvicorn src.api:app --reload` to start server
- [ ] Test `/health` endpoint
- [ ] Test `/search` with a query
- [ ] Test `/ask` with a question about FastAPI
EOF
)"
```

---

**Plan complete and saved.** Two execution options:

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

Which approach?