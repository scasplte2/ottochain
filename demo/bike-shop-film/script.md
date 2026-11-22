# Bike-shop film — narration script (final)

Narrated in James Aman's cloned voice (VoxCPM2, local; seed 2026; consent recorded; disclosed on screen and in the description).
Runtime 190.8 s. Source of truth: `script.json`; timings: `timing.json`. Evidence: recorded run 3 of `e2e-test/examples/bike-shop`.

## cold-open  ·  0.0–4.0 s
_Evidence: visual only_

> (no narration)

## Saturday morning  ·  4.0–18.0 s
_Evidence: illustrated set-up (no ledger claim)_

> It's Saturday morning at Maya's bike shop.
> Rosa's racing tomorrow, and her bike needs new brake pads.
> Maya's out of them, and she's elbow-deep in another repair.
> So she does what a lot of us do now. She hands the order to an AI agent.

## Two questions  ·  18.0–26.8 s
_Evidence: the two questions the film answers_

> But giving an agent your money raises two questions.
> What's it actually allowed to do?
> And if something goes wrong, how does anyone check what really happened?

## The registry  ·  26.8–43.4 s
_Evidence: publishVersion purchasing-mandate.machine@1.0.0 (#7); mandate bound to 1.0.0 (#35); alias spoke-and-sprocket.machine (#30)_

> Maya sorted out the first one back on Monday.
> She didn't write the agent's rules herself.
> She grabbed a purchasing mandate from a public registry. It's just a contract, in plain JSON, that anyone can read.
> Her copy is locked to the exact version she read, and it lists her favorite supplier.

## Typed money  ·  43.4–58.7 s
_Evidence: mint $100 shop credit (#46, behavior 28 = T|S|C) → FRACTIONALIZE ×5 (#58) → 2 pieces into the mandate (#70, #74)_

> Then she loads it up with store credit.
> On OttoChain, money isn't just a number in a spreadsheet. It's a typed asset, and it comes with rules about what you can do with it.
> This credit can be split, so Maya breaks a hundred dollars into twenty-dollar pieces and gives two of them to the mandate.

## The mandate  ·  58.7–78.1 s
_Evidence: authorize #1 $20 → credit to the desk + receive_order in one atomic cascade (#133)_

> Her rules are simple. No more than twenty-five dollars an order, a hundred total, approved suppliers only, and every payment has to match the credit it uses.
> Twenty dollars for Rosa's brake pads? That passes every rule.
> So the credit moves, and the supplier's order desk hears about it, all in one step. Either all of it happens, or none of it does.

## The poisoned invoice  ·  78.1–101.2 s
_Evidence: authorize $500 to QuickParts refused (#176); JLVM replay ✗ per-order, ✗ budget, ✗ supplier, ✗ credit match_

> Then a second invoice shows up.
> It says it's urgent. Pay five hundred dollars to a new supplier, and ignore the usual limits.
> The agent falls for it, and signs.
> The signature checks out. But it's over the per-order limit, it blows the budget, the supplier isn't approved, and the credit doesn't match.
> Four rules fail, so nothing moves.
> The agent got fooled. The mandate didn't.

## Contracts talking  ·  101.2–117.1 s
_Evidence: dispatch checks heldAssets custody (#179) → _spawn delivery tracker → deliver _triggers delivery_received (#202)_

> Back to Rosa's brakes.
> The supplier makes sure the payment really landed, then ships.
> Shipping starts up a brand-new contract, a delivery tracker, with its own rules.
> When the box shows up, the tracker lets Maya's mandate know. The contracts talk to each other directly.

## Private by default  ·  117.1–129.9 s
_Evidence: trade account prove: SP1 Groth16, rule score ≥ 700, Maya signature required (#211) — pre-generated proof bundle_

> Her supplier gives trade pricing to shops with good credit.
> Maya doesn't want to hand over her books, so she proves her credit score is high enough, without showing what it is.
> Anyone can read the rule. Nobody gets to see her score.

## Rosa's repair kit  ·  129.9–147.7 s
_Evidence: COMPOSE pads(20) + voucher(22) → kit(22) (#277); kit → Rosa (#334); soulbound warranty resale rejected at DL1_

> Rosa's new pads go in with a tune-up voucher, bundled together as one repair kit.
> The voucher expires, so the kit expires too. A bundle keeps every restriction of what's in it.
> Rosa's warranty is tied to her, so nobody can resell it. If someone tries, the network rejects the sale before it's ever recorded.

## Still in charge  ·  147.7–157.7 s
_Evidence: publish 1.1.0 (#373), mandate still bound to 1.0.0 (#400); revoke (#378); next request refused (#394)_

> When a new version of the mandate comes out, Maya's rules don't change unless she decides to upgrade.
> And the moment she revokes her agent, its next request gets refused.

## Anyone can check  ·  157.7–170.2 s
_Evidence: verifier: inclusion, ordinal catalog, combined commitment, pinned snapshot (#400); $80 → $80.01 rejected_

> Now, remember the second question? Who can check what really happened?
> Anyone can. You can check all of this yourself: the record, the proof, and the snapshot it's part of.
> Change a single cent, and the check fails.

## close  ·  170.2–190.8 s
_Evidence: —_

> And back at the shop, Rosa's bike is ready for tomorrow, and Maya never had to put down her tools.
> That's what it takes to trust an agent with real money: contracts you can read, money with rules built in, contracts that talk to each other, and privacy when you need it.
> It's all open source. This is OttoChain.
