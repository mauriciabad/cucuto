# Owner log

Verbatim record of the owner's prompts, messages and answers to agents, newest last. Agents append every new owner
message here (unaltered, with date and context) and link the issue or decision it produced. Answers to `question`
issues are linked from here too.

## 2026-10-23 — Kickoff (session 1)

> @docs/project-brief.md You are in charge of this project. you're the first agent to work on it, so the basis must be very well done. you have to create the repo, issue tracker and whatever else needed. you have my gh credentials to do so. a subagen has to do the brand and visual language design with assets and logos and app icons so you can use them from the begining. I fully trust you guys, work autonumously. make subagens in medium effort unless more inteligence is needed, use high for that. keep your context window low, if it increases too much compact or make a new session.
> I will leave my phone connected with root access, but i will be using another macos user on this laptop so i wont see your questions, dont ask them. leave them in issues or womething i can check afterwards.

> btw we should use Kotlin ALWAYS, and latest technologies and versions

> and frequently read the official guidelines to work and follow best practices

> jxl maybe use the rust implementation better if compatible

> btw it is important to keep track of my original prompts, messages and answers. keep that in mind

Resulting decisions: questions go to issues labeled `question`; Kotlin for all code and tooling (see `CLAUDE.md`);
JPEG XL on Android evaluates jxl-rs first (spike issue); this log.

> i added the dns :)

Closed #54 after verifying `cucuto.mauri.app CNAME mauriciabad.github.io` (Cloudflare, DNS only).

> everytime you write in github  it has to be clear it is not me and that it is an AI

Added an AI banner to all 60 issues and the #54 comment; agents now start every GitHub text with the banner and author
commits as `Claude (AI agent)` (rule in `CLAUDE.md`). The first five commits on `main` were authored under the owner's
name before this rule; they carry the `Co-Authored-By: Claude` trailer and were left as is to avoid rewriting history.
