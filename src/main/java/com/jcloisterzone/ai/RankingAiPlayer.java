package com.jcloisterzone.ai;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import com.jcloisterzone.Player;
import com.jcloisterzone.action.AbbeyPlacementAction;
import com.jcloisterzone.action.MeepleAction;
import com.jcloisterzone.action.PlayerAction;
import com.jcloisterzone.action.TilePlacementAction;
import com.jcloisterzone.action.UndeployAction;
import com.jcloisterzone.ai.step.DeployMeepleStep;
import com.jcloisterzone.ai.step.PassStep;
import com.jcloisterzone.ai.step.PlaceAbbeyStep;
import com.jcloisterzone.ai.step.PlaceTileStep;
import com.jcloisterzone.ai.step.Step;
import com.jcloisterzone.board.Location;
import com.jcloisterzone.board.Position;
import com.jcloisterzone.board.Rotation;
import com.jcloisterzone.board.Tile;
import com.jcloisterzone.config.Config.DebugConfig;
import com.jcloisterzone.event.SelectActionEvent;
import com.jcloisterzone.event.SelectDragonMoveEvent;
import com.jcloisterzone.figure.Phantom;
import com.jcloisterzone.figure.SmallFollower;
import com.jcloisterzone.game.Game;
import com.jcloisterzone.game.PlayerSlot;
import com.jcloisterzone.game.Snapshot;
import com.jcloisterzone.game.phase.ActionPhase;
import com.jcloisterzone.game.phase.EscapePhase;
import com.jcloisterzone.game.phase.LoadGamePhase;
import com.jcloisterzone.game.phase.PhantomPhase;
import com.jcloisterzone.game.phase.Phase;
import com.jcloisterzone.game.phase.ScorePhase;
import com.jcloisterzone.game.phase.TowerCapturePhase;

public abstract class RankingAiPlayer extends AiPlayer {

    //private Map<Feature, AiScoreContext> scoreCache = new HashMap<>();
    //private List<PositionLocation> hopefulGatePlacements = new ArrayList<PositionLocation>();

//    public Map<Feature, AiScoreContext> getScoreCache() {
//        return scoreCache;
//    }

    private Step bestChain = null;


    protected void popActionChain() {
        if (bestChain.getPrevious() == null) {
            bestChain.performOnServer(getServer());
            bestChain = null;
            return;
        }

        Step step = bestChain;
        while (step.getPrevious().getPrevious() != null) {
            step = step.getPrevious();
        }
        step.getPrevious().performOnServer(getServer());
        step.setPrevious(null); //cut last element from chain
    }

    protected void autosave() {
        DebugConfig debugConfig = game.getConfig().getDebug();
        if (debugConfig != null && debugConfig.getAutosave() != null && debugConfig.getAutosave().length() > 0) {
            Snapshot snapshot = new Snapshot(game, 0);
            if ("plain".equals(debugConfig.getSave_format())) {
                snapshot.setGzipOutput(false);
            }
            try {
                snapshot.save(new File(debugConfig.getAutosave()));
            } catch (Exception e) {
                logger.error("Auto save before ranking failed.", e);
            }
        }
    }

    @Subscribe
    public void selectAction(SelectActionEvent ev) {
        if (isAiPlayerActive()) {
            if (bestChain != null) {
                popActionChain();
            } else {
                new Thread(new SelectActionTask(ev)).start();
            }
        }
    }

    @Subscribe
    public void selectDragonMove(SelectDragonMoveEvent ev) {
        if (isAiPlayerActive()) {
             new Thread(new SelectDragonMoveTask(ev)).start();
        }
    }

    //TODO is there faster game copying without snapshot? or without re-creating board and tile instances
    private Game copyGame(Object gameListener) {
        Snapshot snapshot = new Snapshot(game, 0);
        Game copy = snapshot.asGame();
        copy.setConfig(game.getConfig());
        copy.getEventBus().register(gameListener);
        LoadGamePhase phase = new LoadGamePhase(copy, snapshot, null);
        phase.setSlots(new PlayerSlot[0]);
        copy.getPhases().put(phase.getClass(), phase);
        copy.setPhase(phase);
        phase.startGame();
        return copy;
    }

    class SelectActionTask implements Runnable {
        Deque<Step> queue = new LinkedList<Step>();

        private final SelectActionEvent rootEv;

        private Step step = null;
        private Step bestSoFar = null;

        private SavePointManager spm;
        private Game game;

        public SelectActionTask(SelectActionEvent rootEv) {
            this.rootEv = rootEv;
        }

        private void dbgPringHeader() {
            StringBuilder sb = new StringBuilder("*** ranking start * ");
            sb.append(game.getPhase().getClass().getSimpleName());
            sb.append(" * ");
            Player p = game.getActivePlayer();
            sb.append(p.getNick());
            sb.append(" ***");
            System.out.println(sb);
        }

        private void dbgPringFooter() {
            System.out.println("=== selected chain (reversed) " + bestSoFar.getRanking());
            Step step = bestSoFar;
            while (step != null) {
                System.out.print("  - ");
                System.out.println(step.toString());
                step = step.getPrevious();
            }
            System.out.println("*** ranking end ***");
        }

        private void dbgPringStep(Step step, boolean isFinal) {
            Step s = step;
            StringBuilder sb = new StringBuilder("  ");
            while (s.getPrevious() != null) {
                sb.append("  ");
                s = s.getPrevious();
            }
            sb.append("- ").append(step.toString()).append(" ");
            if (isFinal) {
                while (sb.length() < 80) {
                    sb.append(".");
                }
                //if (step.ranking > 0) sb.append(" ");
                sb.append(" ").append(String.format(Locale.ROOT, "%.5f", step.getRanking()));
            }
            System.out.println(sb);
        }


        @Override
        public void run() {
            boolean dbgPrint = true;
            try {
                autosave();

                this.game = copyGame(this);
                if (dbgPrint) dbgPringHeader();

                spm = new SavePointManager(game);
                spm.startRecording();

                handleActionEvent(rootEv);

                while (!queue.isEmpty()) {
                    step = queue.removeFirst();
                    spm.restore(step.getSavePoint());
                    step.performLocal(game);
                    boolean isFinal = phaseLoop();
                    if (isFinal) {
                        rankStepChain(step);
                    }
                    if (dbgPrint) dbgPringStep(step, isFinal);
                }
                if (dbgPrint) dbgPringFooter();
                bestChain = bestSoFar;
                popActionChain();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                selectDummyAction(rootEv.getActions(), rootEv.isPassAllowed());
            }
        }

        @SuppressWarnings("unchecked")
        private boolean phaseLoop() {
            Phase phase = game.getPhase();
            List<Class<? extends Phase>> allowed = Lists.newArrayList(ActionPhase.class, EscapePhase.class, TowerCapturePhase.class);
            while (!phase.isEntered()) {
                if (!Iterables.contains(allowed, phase.getClass())) {
                    return true;
                }
                phase.setEntered(true);
                phase.enter();
                phase = game.getPhase();
            }
            return false;
        }

        private void rankStepChain(Step step) {
            step.setRanking(rank(game));
            if (bestSoFar == null || step.getRanking() > bestSoFar.getRanking()) {
                bestSoFar = step;
            }
        }

        @Subscribe
        public void handleActionEvent(SelectActionEvent ev) {
            List<MeepleAction> meepleActions = new ArrayList<MeepleAction>();

            for (PlayerAction action : ev.getActions()) {
                if (action instanceof MeepleAction) {
                    meepleActions.add((MeepleAction) action);
                } else if (action instanceof TilePlacementAction) {
                    handleTilePlacementAction((TilePlacementAction) action);
                } else if (action instanceof AbbeyPlacementAction) {
                    handleAbbeyPlacement((AbbeyPlacementAction) action);
                } else if (action instanceof UndeployAction ) {
                    //hack, AI never use escape, TODO
                    //doesnt work
//                    if (action.getName().equals(SiegeCapability.UNDEPLOY_ESCAPE)) {
//                        getServer().pass();
//                        return;
//                    }
                }
            }

            if (!meepleActions.isEmpty()) {
                handleMeepleActions(preprocessMeepleActions(meepleActions));
            }

            if (ev.isPassAllowed()) {
                queue.addFirst(new PassStep(step, spm.save()));
            }


//          if (action instanceof BarnAction) {
//          BarnAction ba = (BarnAction) action;
//          rankMeeplePlacement(currTile, ba, Barn.class, pos, ba.get(pos));
//      }
//      if (action instanceof FairyAction) {
//          rankFairyPlacement(currTile, (FairyAction) action);
//      }
//      if (action instanceof TowerPieceAction) {
//          rankTowerPiecePlacement(currTile, (TowerPieceAction) action);
//      }

        }


        protected void handleTilePlacementAction(TilePlacementAction action) {
            SavePoint savePoint = spm.save();

            for (Entry<Position, Set<Rotation>> entry : action.getAvailablePlacements().entrySet()) {
                Position pos = entry.getKey();
                for (Rotation rot : entry.getValue()) {
                    queue.addFirst(new PlaceTileStep(step, savePoint, action, rot, pos));
                }
            }
        }

        protected void handleAbbeyPlacement(AbbeyPlacementAction action) {
            SavePoint savePoint = spm.save();
            for (Position pos : action.getSites()) {
                queue.addFirst(new PlaceAbbeyStep(step, savePoint, action, pos));
            }
        }

        protected Collection<MeepleAction> preprocessMeepleActions(List<MeepleAction> actions) {
            if (game.getPhase() instanceof PhantomPhase) return Collections.emptyList();
            boolean hasSmallFollower = false;
            boolean hasPhantom = false;
            for (MeepleAction a : actions) {
                if (a instanceof MeepleAction && a.getMeepleType().equals(SmallFollower.class))
                    hasSmallFollower = true;
                if (a instanceof MeepleAction && a.getMeepleType().equals(Phantom.class))
                    hasPhantom = true;
            }
            if (hasSmallFollower && hasPhantom) {
                return Collections2.filter(actions, new Predicate<MeepleAction>() {
                    @Override
                    public boolean apply(MeepleAction a) {
                        return !(a.getMeepleType().equals(Phantom.class));
                    }
                });
            }
            return actions;
        }

        protected void handleMeepleActions(Collection<MeepleAction> actions) {
           Tile currTile = game.getCurrentTile();
           Position pos = currTile.getPosition();

            SavePoint savePoint = spm.save();

            for (MeepleAction action : actions) {
                Set<Location> locations = action.getLocationsMap().get(pos);
                if (locations == null) continue;

                for (Location loc : locations) {
                    queue.addFirst(new DeployMeepleStep(step, savePoint, action, pos, loc));
                }
            }
        }

        // ---- refactor done boundary -----



//        protected void rankFairyPlacement(Tile currTile, FairyAction action) {
//            SavePoint sp = spm.save();
//            for (Position pos: action.getSites()) {
//                game.getPhase().moveFairy(pos);
//                double currRank = rank(game);
//                if (currRank > bestSoFar.getRank()) {
//                    bestSoFar = new PositionRanking(currRank, currTile.getPosition(), currTile.getRotation());
//                    bestSoFar.getSelectedActions().add(new SelectedAction(action, pos, null));
//                }
//                spm.restore(sp);
//            }
//        }

//        protected void rankTowerPiecePlacementOnTile(final Tile currTile, final TowerPieceAction towerPieceAction, final Position towerPiecePos) {
//            this.interactionHandler = new AiInteractionAdapter() {
//                public void selectAction(List<PlayerAction> actions, boolean canPass) {
//                    phaseLoop();
//                    SavePoint sp = spm.save();
//                    TakePrisonerAction prisonerAction = (TakePrisonerAction) actions.get(0);
//                    for (Entry<Position, Set<Location>> entry : prisonerAction.getLocationsMap().entrySet()) {
//                        Position pos = entry.getKey();
//                        for (Location loc : entry.getValue()) {
//                            for (Meeple m : getBoard().get(pos).getFeature(loc).getMeeples()) {
//                                game.getPhase().takePrisoner(pos, loc, m.getClass(), m.getPlayer().getIndex());
//                                double currRank = rank();
//                                if (currRank > bestSoFar.getRank()) {
//                                    bestSoFar = new PositionRanking(currRank, currTile.getPosition(), currTile.getRotation());
//                                    Deque<SelectedAction> sa = bestSoFar.getSelectedActions();
//                                    sa.add(new SelectedAction(towerPieceAction, towerPiecePos, null));
//                                    sa.add(new SelectedAction(prisonerAction, pos, loc, m.getClass(), m.getPlayer()));
//                                }
//                                spm.restore(sp);
//                            }
//                        }
//                    }
//                };
//            };
//            game.getPhase().placeTowerPiece(towerPiecePos);
//        }

//        protected void rankTowerPiecePlacement(Tile currTile,TowerPieceAction action) {
//            AiInteraction interactionHandlerBackup = this.interactionHandler;
//            SavePoint sp = spm.save();
//            for (Position pos: action.getSites()) {
//                rankTowerPiecePlacementOnTile(currTile, action, pos);
//                spm.restore(sp);
//            }
//
//            this.interactionHandler = interactionHandlerBackup;
//        }


    }

    class SelectDragonMoveTask implements Runnable {
        private SelectDragonMoveEvent ev;

        public SelectDragonMoveTask(SelectDragonMoveEvent ev) {
            this.ev = ev;
        }

        @Override
        public void run() {
            try {
                //TODO
                throw new UnsupportedOperationException("use task from legacy ai player");
            } catch (Exception e) {
                 handleRuntimeError(e);
                 selectDummyDragonMove(ev.getPositions(), ev.getMovesLeft());
            }
        }
    }





//    public void cleanRanking() {
//        bestSoFar = null;
//        //scoreCache.clear();
//    }

    abstract protected double rank(Game game);

//    @Override
//    protected void handleRuntimeError(Exception e) {
//        super.handleRuntimeError(e);
//        cleanRanking();
//        if (original != null) {
//            restoreGame();
//        }
//    }

//    class RankingInteractionHanlder extends AiInteractionAdapter {
//        @Override
//        public void selectAction(List<PlayerAction> actions, boolean canPass) {
//            if (game.getPhase() instanceof PhantomPhase) return;
//            boolean hasSmallFollower = false;
//            boolean hasPhantom = false;
//            for (PlayerAction a : actions) {
//                if (a instanceof MeepleAction && ((MeepleAction) a).getMeepleType().equals(SmallFollower.class)) hasSmallFollower = true;
//                if (a instanceof MeepleAction && ((MeepleAction) a).getMeepleType().equals(Phantom.class)) hasPhantom = true;
//            }
//            if (hasSmallFollower && hasPhantom) {
//                rankAction(Collections2.filter(actions, new Predicate<PlayerAction>() {
//                    @Override
//                    public boolean apply(PlayerAction a) {
//                        return !(a instanceof MeepleAction && ((MeepleAction) a).getMeepleType().equals(Phantom.class));
//                    }
//                }));
//            } else {
//                rankAction(actions);
//            }
//        }
//    }

//    class DefaultInteraction extends AiInteractionAdapter {
//
//        @Override
//        public void selectAction(List<PlayerAction> actions, boolean canPass) {
//            if (actions.size() > 0) {
//                PlayerAction firstAction = actions.get(0);
//
//                if (firstAction instanceof TilePlacementAction) {
//                    selectTilePlacement((TilePlacementAction) firstAction);
//                    return;
//                }
//                if (firstAction instanceof AbbeyPlacementAction) {
//                    selectAbbeyPlacement((AbbeyPlacementAction) firstAction);
//                    return;
//                }
//                if (firstAction instanceof UndeployAction ) {
//                    //hack, ai never use escape, TODO
//                    if (firstAction.getName().equals(SiegeCapability.UNDEPLOY_ESCAPE)) {
//                        getServer().pass();
//                    }
//                }
//            }
//
//            if (bestSoFar == null) { //loaded game or wagon phase or phantom phase
//                backupGame();
//                if (canPass) rankPass();
//                rankAction(actions);
//                restoreGame();
//            }
//
//            Deque<SelectedAction> selected = bestSoFar.getSelectedActions();
//            SelectedAction sa = selected.pollFirst();
//            if (sa != null) {
//                //logger.info("Polling " + sa.action);
//                if (sa.action instanceof MeepleAction) {
//                    MeepleAction action = (MeepleAction) sa.action;
//                    //debug, should never happen, but it happens sometimes when Tower game is enabled
////                    try {
////                        getPlayer().getMeepleFromSupply(action.getMeepleType());
////                    } catch (NoSuchElementException e) {
////                        logger.error(e.getMessage(), e);
////                        throw e;
////                    }
//                    action.perform(getServer(), sa.position, sa.location);
//                } else if (sa.action instanceof BarnAction) {
//                    BarnAction action = (BarnAction) sa.action;
//                    action.perform(getServer(), sa.position, sa.location);
//                } else if (sa.action instanceof SelectTileAction) {
//                    SelectTileAction action = (SelectTileAction) sa.action;
//                    action.perform(getServer(), sa.position);
//                } else if (sa.action instanceof SelectFollowerAction) {
//                    SelectFollowerAction action = (SelectFollowerAction) sa.action;
//                    action.perform(getServer(), sa.position, sa.location, sa.meepleType, sa.meepleOwner);
//                } else {
//                    throw new UnsupportedOperationException("Unhandled action type " + sa.action.getName()); //should never happen
//                }
//            } else {
//                getServer().pass();
//            }
//
//            if (selected.isEmpty()) {
//                cleanRanking();
//            }
//        }
//    }

}
