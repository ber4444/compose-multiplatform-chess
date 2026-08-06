import re

with open("/Users/presence/Downloads/chess plan.md", "r") as f:
    content = f.read()

old_text = "- [ ] Review the `summary.json` against the acceptance bar and record representative failures."

new_text = """- [x] Review the `summary.json` against the acceptance bar and record representative failures.
  - **Verdict (2026-08-06): FAILED**. The generated samples fail the usefulness gate. The LLM (and fallback templates) simply regurgitate the exact first sentence of the Opening Explainer or return blank answers. They fail to shape strategic answers or pass shape checks. Furthermore, Opening Explainers frequently hit `provider_error` due to high latency. R-1 is NOT closed."""

content = content.replace(old_text, new_text)

with open("/Users/presence/Downloads/chess plan.md", "w") as f:
    f.write(content)
print("Verdict recorded")
