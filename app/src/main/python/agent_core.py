import sys
from duckduckgo_search import DDGS

def run_web_search(query: str) -> str:
    """Performs a live DuckDuckGo web search and returns formatted results."""
    try:
        results = DDGS().text(query, max_results=3)
        if not results:
            return "No search results found."
        
        output = []
        for r in results:
            title = r.get('title', 'No Title')
            href = r.get('href', '#')
            body = r.get('body', '')
            output.append(f"**{title}**\n{href}\n{body}\n")
        return "\n".join(output)
    except Exception as e:
        return f"Search execution failed: {str(e)}"
