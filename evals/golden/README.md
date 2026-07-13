# Golden-set candidates

`candidates.json` contains 100 semantically distinct positions generated from the checked-in Lichess
opening lines. Each FEN is the position immediately before `bestMoveUci`; tests reject duplicate
FEN/best-move/opening-line tuples. They remain candidates: the repository owner must hand-check and
correct best-move or concept labels before treating the scorecard as article-grade evidence. The eval
task is useful before that review, but it does not replace it.
