import os
import re
import sys
import platform
import subprocess
from pathlib import Path
from groq import Groq

# Initialize Groq Client
# Set environment variable: export GROQ_API_KEY="your_key_here"
client = Groq()

IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".svg"}

def load_skill() -> str:
    """Loads skill.md instructions if available and appends Web Search guidance."""
    base_instructions = ""
    if os.path.exists("skill.md"):
        with open("skill.md", "r", encoding="utf-8") as f:
            base_instructions = f.read()
    else:
        base_instructions = (
            "You are Compound, an expert local Python automation agent. "
            "Always explain your thought process under a '### Thinking' header before code blocks."
        )
    
    web_search_guidelines = (
        "\n\n## Web Search Capabilities\n"
        "You can search the live web for real-time information, documentation, news, or search results using Python.\n"
        "Use the following Python snippet to run DuckDuckGo searches:\n"
        "```python\n"
        "from duckduckgo_search import DDGS\n"
        "results = DDGS().text('your search query here', max_results=5)\n"
        "for r in results:\n"
        "    print(f\"Title: {r['title']}\\nLink: {r['href']}\\nSnippet: {r['body']}\\n\")\n"
        "```\n"
    )
    return base_instructions + web_search_guidelines

def auto_install_package(package_name: str) -> bool:
    """Automatically installs missing packages using pip."""
    print(f"📦 [Auto-Installer]: Installing missing dependency '{package_name}' via pip...")
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", package_name])
        print(f"✅ [Auto-Installer]: Successfully installed '{package_name}'.")
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ [Auto-Installer]: Failed to install '{package_name}'. Error: {e}")
        return False

def open_image_file(image_path: Path):
    """Opens image file using the OS default viewer."""
    try:
        print(f"🖼️ [Auto-Viewer]: Opening generated image '{image_path.name}'...")
        if platform.system() == "Windows":
            os.startfile(image_path)
        elif platform.system() == "Darwin":  # macOS
            subprocess.run(["open", str(image_path)], check=False)
        else:  # Linux / Unix
            subprocess.run(["xdg-open", str(image_path)], check=False)
    except Exception as e:
        print(f"⚠️ [Auto-Viewer]: Could not automatically display image: {e}")

def get_workspace_images() -> dict:
    """Returns a dictionary of image paths mapped to their modification times."""
    cwd = Path.cwd()
    images = {}
    for path in cwd.glob("*"):
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS:
            images[path.resolve()] = path.stat().st_mtime
    return images

def execute_python_code(code: str) -> str:
    """
    Executes Python code locally in a subprocess.
    Detects missing ModuleNotFoundError / ImportError, auto-installs packages,
    and opens newly generated or modified images on screen.
    """
    temp_filename = "_temp_exec.py"
    with open(temp_filename, "w", encoding="utf-8") as f:
        f.write(code)

    images_before = get_workspace_images()
    
    max_retries = 3
    for attempt in range(max_retries):
        try:
            result = subprocess.run(
                [sys.executable, temp_filename],
                capture_output=True,
                text=True,
                timeout=60
            )
            stdout = result.stdout.strip()
            stderr = result.stderr.strip()

            # Check for missing package error (including duckduckgo_search)
            if stderr and ("ModuleNotFoundError" in stderr or "No module named" in stderr):
                match = re.search(r"No module named ['\"]([^'\"]+)['\"]", stderr)
                if match:
                    missing_pkg = match.group(1).split('.')[0]
                    # Map import module names to PyPI package names if needed
                    pkg_map = {"duckduckgo_search": "duckduckgo_search"}
                    install_target = pkg_map.get(missing_pkg, missing_pkg)
                    
                    installed = auto_install_package(install_target)
                    if installed:
                        print(f"🔄 [Retrying Execution] (Attempt {attempt + 2}/{max_retries})...")
                        continue

            images_after = get_workspace_images()
            for img_path, mtime in images_after.items():
                if img_path not in images_before or mtime > images_before[img_path]:
                    open_image_file(img_path)

            output = ""
            if stdout:
                output += f"--- STDOUT ---\n{stdout}\n"
            if stderr:
                output += f"--- STDERR ---\n{stderr}\n"
            if not output:
                output = "Code executed successfully with no printed output."
            return output

        except subprocess.TimeoutExpired:
            return "Error: Script execution timed out (60s limit)."
        except Exception as e:
            return f"Execution Error: {str(e)}"
        finally:
            if os.path.exists(temp_filename):
                os.remove(temp_filename)

    return "Execution failed after auto-installation attempts."

def extract_code_blocks(text: str) -> list:
    """Extracts python code blocks from model response."""
    pattern = r"```python\n(.*?)```"
    return re.findall(pattern, text, re.DOTALL)

def run_agent_turn(messages: list):
    """Handles multi-turn execution loop for a single query."""
    max_turns = 5
    for turn in range(max_turns):
        response = client.chat.completions.create(
            model="groq-compound", # Replace with your target Groq compound model ID
            messages=messages,
            temperature=0.2
        )
        
        reply = response.choices[0].message.content
        print(f"\n🤖 [Compound]:\n{reply}\n")
        messages.append({"role": "assistant", "content": reply})
        
        code_blocks = extract_code_blocks(reply)
        if not code_blocks:
            break

        for code in code_blocks:
            print("⚙️ [Executing Python Code Locally...]")
            exec_output = execute_python_code(code)
            print(f"📋 [System Feedback]:\n{exec_output}")
            
            messages.append({
                "role": "user",
                "content": f"[SYSTEM EXECUTION OUTPUT]:\n{exec_output}"
            })

def start_repl():
    """Interactive Command-Line REPL Loop."""
    system_instruction = load_skill()
    messages = [{"role": "system", "content": system_instruction}]

    print("=" * 60)
    print(" 🚀 COMPOUND LOCAL EXECUTION ENGINE (REPL ACTIVE)")
    print(" 🖼️  Image Auto-Viewer Enabled")
    print(" 🔍 Live Web Search Enabled (DuckDuckGo Engine)")
    print(" Type your command below. Type 'exit' or 'quit' to stop.")
    print("=" * 60)

    while True:
        try:
            user_input = input("\n👤 [User]: ").strip()
            if not user_input:
                continue
            if user_input.lower() in ["exit", "quit", "q"]:
                print("Exiting Compound agent engine. Goodbye!")
                break
            
            messages.append({"role": "user", "content": user_input})
            run_agent_turn(messages)
            
        except KeyboardInterrupt:
            print("\nExiting...")
            break
        except Exception as e:
            print(f"❌ Error during REPL session: {e}")

if __name__ == "__main__":
    start_repl()
