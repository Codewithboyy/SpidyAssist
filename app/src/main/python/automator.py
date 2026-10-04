# app/src/main/python/automator.py
from duckduckgo_search import DDGS

def run_web_search(query: str) -> str:
    results = DDGS().text(query, max_results=3)
    output = []
    for r in results:
        output.append(f"Title: {r['title']}\nSnippet: {r['body']}\n")
    return "\n".join(output)
