(ns finishing.facts
  "Per-jurisdiction building-finishing regulatory catalog -- the spec-basis
  table the Finishing Governor checks every `:schedule-finishing-
  operation` proposal against ('did the advisor cite an OFFICIAL public
  source for this jurisdiction's pre-work hazmat-survey / at-height
  fall-protection requirements, or did it invent one?'). Same honest-
  coverage discipline `demolition.facts` (`cloud-itonami-isic-4311`) /
  `construction.facts` (`cloud-itonami-isic-4211`) established for this
  fleet: a jurisdiction not in this table has NO spec-basis, full stop --
  the advisor must not fabricate one, and the governor holds if it tries.

  Coverage is reported HONESTLY (see `coverage`); this is a STARTING
  catalog (JPN/USA/DEU), not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to `catalog`,
  cite a real source, done -- never invent a jurisdiction's requirements
  to make coverage look bigger.

  Two independent legal bases, both citable and both real:

    1. `:hazmat-survey-basis` -- the PRE-WORK lead-paint / asbestos survey
       duty that applies before plastering/painting/glazing/tiling/
       finish-carpentry work disturbs an existing painted or built-up
       surface in an older building. This is the SAME kind of duty
       `demolition.facts` cites for demolition/renovation work generally
       -- building-completion/finishing work on an existing structure is
       squarely inside its scope, not a separate carve-out.
    2. `:fall-protection-basis` -- the at-height (scaffold/ladder/lift)
       fall-protection duty that applies once a finishing crew (exterior
       painting, plastering, glazing above ground level, etc.) works
       above a jurisdiction-specific trigger height. This REPLACES
       `demolition.facts`'s `:demolition-notification-basis` /
       `:notification-lead-days` concept, which has no natural analog
       here (finishing work is not subject to a fixed-day advance
       regulatory filing the way demolition is) -- fall-protection height
       is this domain's OWN real, independently-recheckable numeric
       trigger.

  `:threshold-model` mirrors the SAME honest quantitative/qualitative
  split `demolition.facts/threshold-model` established, applied here to
  `:fall-protection-trigger-height-m` instead of a notification lead-time:
    :quantitative -- the jurisdiction's OSH law states a fixed numeric
                     height that triggers a fall-protection duty (Japan's
                     2m under the Industrial Safety and Health Regulations;
                     the USA's 6ft/1.8m under OSHA 1926.501).
                     `fall-protection-noncompliant?` can independently
                     recompute a HARD hold from this.
    :qualitative  -- the jurisdiction imposes a documented risk-assessment
                     (Gefährdungsbeurteilung) duty with NO single fixed
                     EU-wide/federal numeric trigger height (Germany/EU,
                     TRBS 2121 under the Betriebssicherheitsverordnung --
                     the trigger height is scenario-dependent, not one
                     fixed number). This actor does NOT invent a height to
                     make this jurisdiction look automatable --
                     `fall-protection-noncompliant?` returns `:qualitative`
                     and `:schedule-finishing-operation` for that
                     jurisdiction is left to the ordinary confidence-floor
                     gate (see `finishing.governor` ns docstring) rather
                     than a fabricated HARD numeric rule.

  DEU is used as the EU-jurisdiction proxy, the SAME convention
  `demolition.facts`/`construction.facts`/`aerospace.facts` established --
  there is no ISO-3166 alpha-3 code for the EU itself, and the asbestos
  directive is transposed into national law per member state (here:
  Germany's Gefahrstoffverordnung / TRGS 519), so the citation lists BOTH
  the EU directive and its German transposition rather than inventing an
  EU country code. All citations below were independently verified against
  their official source before being written (osha.gov / ecfr.gov /
  laws.e-gov.go.jp / eur-lex.europa.eu / baua.de)."
  )

(def catalog
  "iso3 -> requirement map. `:hazmat-survey-basis` / `:fall-protection-
  basis` / their `-provenance` pairs, plus `:owner-authority`, are the
  G2-style citation the governor requires before a `:schedule-finishing-
  operation` proposal can ever commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省（労働基準監督署）"
          :hazmat-survey-basis "石綿障害予防規則（平成17年厚生労働省令第21号）第3条（建築物・工作物の解体・改修等の作業を行う前に、石綿使用の有無について事前調査を行う義務 -- 内装仕上げ・改修工事等も対象）"
          :hazmat-survey-provenance "https://laws.e-gov.go.jp/law/417M60000100021"
          :fall-protection-basis "労働安全衛生規則第518条（作業床の設置等）-- 高さが2メートル以上の箇所で作業を行う場合、墜落により労働者に危険を及ぼすおそれのあるときは作業床を設置し、それが困難なときは防網の設置・要求性能墜落制止用器具の使用等の措置を講じる義務"
          :fall-protection-provenance "https://laws.e-gov.go.jp/law/347M50002000032"
          :threshold-model :quantitative
          :fall-protection-trigger-height-m 2.0
          :threshold-note "高さ2メートル以上の箇所での作業について、作業床の設置または防網・墜落制止用器具等の代替措置が義務（労働安全衛生規則第518条）"}
   "USA" {:name "United States"
          :owner-authority "U.S. Environmental Protection Agency (EPA), Lead Renovation, Repair and Painting (RRP) Program / Occupational Safety and Health Administration (OSHA), U.S. Department of Labor"
          :hazmat-survey-basis "40 CFR Part 745, Subpart E (EPA Lead Renovation, Repair, and Painting (RRP) Rule -- firms disturbing more than 6 sq ft of interior / 20 sq ft of exterior painted surface in a pre-1978 residence or child-occupied facility must be certified and follow lead-safe work practices before the work begins)"
          :hazmat-survey-provenance "https://www.ecfr.gov/current/title-40/chapter-I/subchapter-R/part-745/subpart-E"
          :fall-protection-basis "29 CFR 1926.501 (OSHA -- Duty to have fall protection, Subpart M: employees on a walking/working surface with an unprotected side or edge at 6 feet (1.8 m) or more above a lower level must be protected by guardrail systems, safety net systems, or personal fall arrest systems)"
          :fall-protection-provenance "https://www.osha.gov/laws-regs/regulations/standardnumber/1926/1926.501"
          :threshold-model :quantitative
          :fall-protection-trigger-height-m 1.8
          :threshold-note "6 feet (1.8 m) or more above a lower level triggers the OSHA 1926.501 fall-protection duty for construction/finishing work."}
   "DEU" {:name "Germany (EU jurisdiction proxy, see ns docstring)"
          :owner-authority "Bundesministerium für Arbeit und Soziales (BMAS) / Bundesanstalt für Arbeitsschutz und Arbeitsmedizin (BAuA); EU level: European Agency for Safety and Health at Work (EU-OSHA)"
          :hazmat-survey-basis "Directive 2009/148/EC (protection of workers from the risks related to exposure to asbestos at work, codified version) Art.11 -- a documented plan of work must be drawn up before demolition or asbestos-removal/renovation work is started; nationally transposed via the Gefahrstoffverordnung (GefStoffV) and TRGS 519 (Technical Rule for Hazardous Substances -- Asbestos: demolition, reconstruction or maintenance work)"
          :hazmat-survey-provenance "https://eur-lex.europa.eu/eli/dir/2009/148/oj"
          :framework-provenance "https://www.baua.de/EN/Service/Technical-rules/TRGS/TRGS-519"
          :fall-protection-basis "TRBS 2121 (Technische Regel für Betriebssicherheit -- Gefährdung von Beschäftigten durch Absturz), issued by BAuA under the Betriebssicherheitsverordnung (BetrSichV) -- requires a documented risk assessment (Gefährdungsbeurteilung) for fall hazards; the required height at which technical fall-protection measures apply is SCENARIO-DEPENDENT (assessment-based), not one single fixed federal/EU-wide numeric trigger the way JPN/USA have."
          :fall-protection-provenance "https://www.baua.de/DE/Angebote/Regelwerk/TRBS/TRBS-2121"
          :threshold-model :qualitative
          :fall-protection-trigger-height-m nil
          :threshold-note "EU/ドイツの墜落防止規則（TRBS 2121、BetrSichVに基づく）は危険性評価（Gefährdungsbeurteilung）に基づく措置義務を課すのみで、日本の2m・米国の6ft(1.8m)のような固定の数値トリガーはEU全域では法定されていない -- ここで数値を創作しない。"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any `:schedule-finishing-operation` proposal
  that tries to cite one."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4330 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `finishing.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn fall-protection-noncompliant?
  "Independently recompute whether `site`'s own recorded ground-truth
  fields -- `:scaffold-working-height-m` (how high the finishing crew is
  actually working) and `:fall-protection-installed?` (whether a
  guardrail/net/personal-fall-arrest measure is actually in place) --
  leave the site out of compliance with `iso3`'s fall-protection trigger.

  Three-valued, deliberately (the same shape
  `demolition.facts/notification-lead-insufficient?` established):
    true         -- a :quantitative jurisdiction (Japan, USA) whose own
                    numeric trigger height is independently confirmed MET
                    OR EXCEEDED by the site's own recorded actual working
                    height, AND no fall-protection measure is recorded as
                    installed -- a bright-line legal violation. The
                    Finishing Governor turns this into a HARD,
                    un-overridable hold on `:schedule-finishing-
                    operation`.
    false        -- either below the trigger height, or at/above it with
                    a fall-protection measure already recorded installed.
    :qualitative -- a jurisdiction with NO fixed numeric trigger (DEU/EU).
                    This actor cannot independently confirm
                    'compliant'/'noncompliant' by arithmetic alone -- the
                    law itself requires a documented risk-assessment
                    judgment call. Never fabricate a trigger height here.
    nil          -- no spec-basis at all for `iso3` (a jurisdiction not in
                    `catalog`)."
  [iso3 {:keys [scaffold-working-height-m fall-protection-installed?]}]
  (when-let [{:keys [threshold-model fall-protection-trigger-height-m]} (spec-basis iso3)]
    (case threshold-model
      :quantitative
      (boolean (and (number? scaffold-working-height-m)
                    (>= scaffold-working-height-m fall-protection-trigger-height-m)
                    (not (true? fall-protection-installed?))))
      :qualitative
      :qualitative
      nil)))
