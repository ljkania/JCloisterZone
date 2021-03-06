package com.jcloisterzone.figure;

import com.jcloisterzone.Player;
import com.jcloisterzone.feature.City;
import com.jcloisterzone.feature.Feature;
import com.jcloisterzone.feature.visitor.FeatureVisitor;
import com.jcloisterzone.game.Game;

public class Mayor extends Follower {

    private static final long serialVersionUID = -7602411772187519451L;

    public Mayor(Game game, Integer idSuffix, Player player) {
        super(game, idSuffix, player);
    }

    static class PennatsCountingVisitor implements FeatureVisitor<Integer> {
        int pennats = 0;

        @Override
        public VisitResult visit(Feature feature) {
            City c = (City) feature;
            pennats += c.getPennants();
            return VisitResult.CONTINUE;
        }

        @Override
        public Integer getResult() {
            return pennats;
        }
    }

    @Override
    public int getPower() {
        //TODO not effective - city is walked twice during scoring
        return getFeature().walk(new PennatsCountingVisitor());
    }

    @Override
    public DeploymentCheckResult isDeploymentAllowed(Feature f) {
        if (!(f instanceof City)) {
            return new DeploymentCheckResult("Mayor must be placed in city only.");
        }
        return super.isDeploymentAllowed(f);
    }

}
