(() => {
  "use strict";

  const token = new URLSearchParams(window.location.search).get("token") || "";
  const state = { assessment: null, answers: {}, submitting: false };
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const toneClass = tone => tone && tone !== "neutral" ? `tone-${tone}` : "";
  const escapeSelector = value => window.CSS?.escape ? CSS.escape(value) : value.replace(/[^a-z0-9_-]/gi, "\\$&");

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function showToast(message) {
    const toast = $("#toast");
    toast.textContent = message;
    toast.classList.add("visible");
    window.setTimeout(() => toast.classList.remove("visible"), 3600);
  }

  function questionValue(question) {
    if (Object.prototype.hasOwnProperty.call(state.answers, question.id)) return state.answers[question.id];
    if (question.type === "multi") return [];
    if (question.type === "text") return "";
    return null;
  }

  function answered(question) {
    const value = questionValue(question);
    if (question.type === "multi") return value.length > 0;
    if (question.type === "text" || question.type === "single") return typeof value === "string" && value.trim() !== "";
    if (question.type === "boolean") return typeof value === "boolean";
    return false;
  }

  function allQuestions() {
    return state.assessment.sections.flatMap(section => section.questions);
  }

  function updateProgress() {
    const questions = allQuestions();
    const complete = questions.filter(answered).length;
    const percent = questions.length ? Math.round((complete / questions.length) * 100) : 0;
    $("#progress-bar").style.width = `${percent}%`;
    $("#progress-label").textContent = `${percent}% complete`;
    $("#question-count").textContent = `${complete} of ${questions.length} decisions answered`;
  }

  function updateOptionSelection(container) {
    $$(".option", container).forEach(option => {
      const input = $("input", option);
      option.classList.toggle("selected", input.checked);
    });
  }

  function renderChoiceQuestion(question, container) {
    const options = el("div", "options");
    const multiple = question.type === "multi";
    question.options.forEach(option => {
      const label = el("label", "option");
      const input = document.createElement("input");
      input.type = multiple ? "checkbox" : "radio";
      input.name = question.id;
      input.value = option.value;
      const current = questionValue(question);
      input.checked = multiple ? current.includes(option.value) : current === option.value;
      const copy = el("span");
      copy.append(el("span", "option-label", option.label));
      if (option.description) copy.append(el("span", "option-description", option.description));
      label.append(input, copy);
      if (option["recommended?"]) label.append(el("span", "recommended-badge", "Recommended"));
      input.addEventListener("change", () => {
        if (multiple) {
          state.answers[question.id] = $$(`input[name="${escapeSelector(question.id)}"]:checked`, options).map(item => item.value);
        } else {
          state.answers[question.id] = input.value;
        }
        container.classList.remove("invalid");
        updateOptionSelection(options);
        updateProgress();
      });
      options.append(label);
    });
    updateOptionSelection(options);
    container.append(options);
  }

  function renderTextQuestion(question, container) {
    const input = document.createElement("textarea");
    input.className = "text-answer";
    input.rows = 4;
    input.maxLength = 20000;
    input.placeholder = question.placeholder || "Type your answer…";
    input.value = questionValue(question);
    input.addEventListener("input", () => {
      state.answers[question.id] = input.value;
      container.classList.remove("invalid");
      updateProgress();
    });
    container.append(input);
  }

  function renderBooleanQuestion(question, container) {
    const options = el("div", "boolean-options options");
    [["true", "Yes", true], ["false", "No", false]].forEach(([raw, labelText, value]) => {
      const label = el("label", "option");
      const input = document.createElement("input");
      input.type = "radio";
      input.name = question.id;
      input.value = raw;
      input.checked = questionValue(question) === value;
      input.addEventListener("change", () => {
        state.answers[question.id] = value;
        container.classList.remove("invalid");
        updateOptionSelection(options);
        updateProgress();
      });
      label.append(input, el("span", "option-label", labelText));
      options.append(label);
    });
    updateOptionSelection(options);
    container.append(options);
  }

  function renderQuestion(question, number) {
    const container = el("div", "question");
    container.dataset.questionId = question.id;
    const head = el("div", "question-head");
    head.append(el("span", "question-number", String(number)));
    const prompt = el("div", "question-prompt", question.prompt);
    if (question["required?"]) prompt.append(el("span", "required", "*"));
    head.append(prompt);
    container.append(head);
    if (question.description) container.append(el("p", "question-description", question.description));

    if (question.type === "single" || question.type === "multi") renderChoiceQuestion(question, container);
    if (question.type === "text") renderTextQuestion(question, container);
    if (question.type === "boolean") renderBooleanQuestion(question, container);
    container.append(el("div", "question-error", "Please answer this required question."));
    return container;
  }

  function renderOverview(assessment) {
    const root = $("#overview");
    root.append(el("div", "eyebrow", assessment.eyebrow || "Candidate target briefing"));
    root.append(el("h1", "", assessment.title));
    root.append(el("p", "lede", assessment.summary));

    const meta = el("div", "candidate-meta");
    [["Version", assessment.candidate.version], ["Revision", assessment.candidate.revision], ["License", assessment.candidate.license]]
      .filter(([, value]) => value)
      .forEach(([label, value]) => meta.append(el("span", "chip", `${label}: ${value}`)));
    root.append(meta);

    if (assessment.recommendation) {
      const box = el("div", `recommendation ${toneClass(assessment.recommendation.tone)}`);
      box.append(el("h3", "", assessment.recommendation.title), el("p", "", assessment.recommendation.body));
      root.append(box);
    }

    if (assessment.facts?.length) {
      const facts = el("div", "facts-grid");
      assessment.facts.forEach(fact => {
        const card = el("div", "fact");
        card.append(el("div", "fact-label", fact.label), el("div", "fact-value", fact.value));
        if (fact.note) card.append(el("div", "fact-note", fact.note));
        facts.append(card);
      });
      root.append(facts);
    }

    if (assessment.findings?.length) {
      const findings = el("div", "findings-grid");
      assessment.findings.forEach(finding => {
        const card = el("div", `finding-card ${toneClass(finding.tone)}`);
        card.append(el("h3", "", finding.title), el("p", "", finding.body));
        findings.append(card);
      });
      root.append(findings);
    }

    if (assessment.sources?.length) {
      const sources = el("div", "sources");
      sources.append(el("strong", "", "Primary sources"));
      assessment.sources.forEach(source => {
        const link = el("a", "", source.label);
        link.href = source.url;
        link.target = "_blank";
        link.rel = "noreferrer noopener";
        sources.append(link);
      });
      root.append(sources);
    }
  }

  function renderSections(assessment) {
    const root = $("#sections");
    let number = 1;
    assessment.sections.forEach((section, index) => {
      const sectionNode = el("section", "document-section");
      sectionNode.id = section.id;
      sectionNode.append(el("div", "section-kicker", `Decision ${String(index + 1).padStart(2, "0")}`));
      sectionNode.append(el("h2", "", section.title));
      if (section.description) sectionNode.append(el("p", "section-intro", section.description));
      if (section.evidence?.length) {
        const evidence = el("div", "evidence-box");
        evidence.append(el("strong", "", "What the investigation found"));
        const list = el("ul");
        section.evidence.forEach(item => list.append(el("li", "", item)));
        evidence.append(list);
        sectionNode.append(evidence);
      }
      section.questions.forEach(question => sectionNode.append(renderQuestion(question, number++)));
      root.append(sectionNode);
    });
  }

  function renderNav(assessment) {
    const nav = $("#sidebar-nav");
    nav.append(el("div", "nav-label", "On this page"));
    const links = [["overview", "Overview"], ...assessment.sections.map(section => [section.id, section.title]), ["final-notes", "Final note"]];
    links.forEach(([id, label], index) => {
      const link = el("a", "nav-link", "");
      link.href = `#${id}`;
      link.append(el("span", "nav-index", String(index).padStart(2, "0")), el("span", "", label));
      link.addEventListener("click", () => $(".sidebar").classList.remove("open"));
      nav.append(link);
    });

    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          $$(".nav-link").forEach(link => link.classList.toggle("active", link.hash === `#${entry.target.id}`));
        }
      });
    }, { rootMargin: "-20% 0px -70% 0px" });
    [$("#overview"), ...$$(`#sections > section`), $("#final-notes")].forEach(section => observer.observe(section));
  }

  function validateRequired() {
    let firstInvalid = null;
    allQuestions().forEach(question => {
      const container = $(`[data-question-id="${escapeSelector(question.id)}"]`);
      const invalid = Boolean(question["required?"]) && !answered(question);
      container.classList.toggle("invalid", invalid);
      if (invalid && !firstInvalid) firstInvalid = container;
    });
    if (firstInvalid) {
      firstInvalid.scrollIntoView({ behavior: "smooth", block: "center" });
      showToast("Please complete the required decisions.");
      return false;
    }
    return true;
  }

  async function post(path, payload = {}) {
    const response = await fetch(`${path}?token=${encodeURIComponent(token)}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    const body = await response.json();
    if (!response.ok || !body.ok) throw new Error(body.error || "Request failed");
    return body;
  }

  function showComplete(title, message) {
    $("#assessment-form").innerHTML = "";
    const screen = el("div", "success-screen");
    screen.append(el("div", "success-icon", "✓"), el("h2", "", title), el("p", "lede", message));
    $("#assessment-form").append(screen);
    $(".action-bar").hidden = true;
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function submit(event) {
    event.preventDefault();
    if (state.submitting || !validateRequired()) return;
    state.submitting = true;
    $("#submit-button").disabled = true;
    try {
      await post("/api/submit", { answers: state.answers, notes: $("#notes").value });
      showComplete("Decisions submitted", "The local server is shutting down. Codex will process your answers and continue in the conversation.");
    } catch (error) {
      state.submitting = false;
      $("#submit-button").disabled = false;
      showToast(error.message);
    }
  }

  async function cancel() {
    if (state.submitting || !window.confirm("Cancel this decision session?")) return;
    state.submitting = true;
    try {
      await post("/api/cancel");
      showComplete("Assessment cancelled", "No decisions were submitted. The local server is shutting down.");
    } catch (error) {
      state.submitting = false;
      showToast(error.message);
    }
  }

  async function boot() {
    try {
      const response = await fetch(`/api/assessment?token=${encodeURIComponent(token)}`, { cache: "no-store" });
      if (!response.ok) throw new Error("The assessment session is unavailable.");
      state.assessment = await response.json();
      document.title = state.assessment.title;
      $("#crumb-candidate").textContent = state.assessment.candidate.name;
      renderOverview(state.assessment);
      renderSections(state.assessment);
      renderNav(state.assessment);
      $("#assessment-form").addEventListener("submit", submit);
      $("#cancel-button").addEventListener("click", cancel);
      $("#menu-button").addEventListener("click", () => $(".sidebar").classList.toggle("open"));
      $("#loading").hidden = true;
      $("#app").hidden = false;
      updateProgress();
    } catch (error) {
      const loading = $("#loading");
      loading.replaceChildren(el("div", "loading-mark", "!"), el("p", "", error.message));
    }
  }

  boot();
})();
