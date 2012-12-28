package SevenZip.Compression.LZMA;

import org.junit.Test;

import java.util.*;

public class BaseLearningTest {
    enum Action {
        LiteralByte {
            @Override
            public int execute(int state) {
                return Base.getNextStateAfterLiteralByte(state);
            }
        }, Match {
            @Override
            public int execute(int state) {
                return Base.getNextStateAfterMatch(state);
            }
        }, ShortRepo {
            @Override
            public int execute(int state) {
                return Base.getNextStateAfterShortRep(state);
            }
        }, LongRep {
            @Override
            public int execute(int state) {
                return Base.getNextStateAfterLongRep(state);
            }
        }, Rep {
            @Override
            public int execute(int state) {
                throw new RuntimeException();
            }
        }, MatchOrRep {
            @Override
            public int execute(int state) {
                throw new RuntimeException();
            }
        };

        public abstract int execute(int state);
    }

    @Test
    public void detectPatterns() {
        final Stack<Action> arr = new Stack<Action>();
        final Set<Chain>[] result = new Set[Base.kNumStates];
        for (int i = 0; i < result.length; i++) {
            result[i] = new HashSet<Chain>();
        }
        createChain(arr, 6, result);
        for (int i = 0; i < result.length; i++) {
            examine(i, result[i]);
        }
    }

    private static void examine(int endState, Set<Chain> chains) {
        System.out.println("for state " + endState);
        while (true) {
            final Set<Action> actions = new HashSet<Action>();
            for (Chain chain : chains) {
                actions.add(chain._actions.remove(0));
            }

            if(actions.contains(Action.ShortRepo) && actions.contains(Action.LongRep)) {
                actions.remove(Action.ShortRepo);
                actions.remove(Action.LongRep);
                actions.add(Action.Rep);
            }
            if((actions.contains(Action.ShortRepo) || actions.contains(Action.LongRep) || actions.contains(Action.Rep)) && actions.contains(Action.Match)) {
                actions.remove(Action.ShortRepo);
                actions.remove(Action.LongRep);
                actions.remove(Action.Rep);
                actions.remove(Action.Match);
                actions.add(Action.MatchOrRep);
            }
            if (actions.size() != 1) {
                System.out.println();
                break;
            }
            System.out.print("  " + actions);
        }
    }

    private void createChain(Stack<Action> arr, int todo, Set<Chain>[] result) {
        if (todo == 0) {
            for (int i = 0; i < Base.kNumStates; i++) {
                evaluate(arr, result, i);
            }
        } else {
            for (Action action : new Action[]{Action.LiteralByte, Action.Match, Action.ShortRepo, Action.LongRep}) {
                arr.push(action);
                createChain(arr, todo - 1, result);
                arr.pop();
            }
        }
    }

    private void evaluate(Stack<Action> actions, Set<Chain>[] result, int startState) {
        int state = startState;
        for (Action action : actions) {
            state = action.execute(state);
        }
        result[state].add(new Chain(startState, actions));
    }

    private static class Chain {
        private final int _startState;
        private final List<Action> _actions;

        private Chain(int startState, Stack<Action> actions) {
            _startState = startState;
            _actions = new ArrayList<Action>(actions);
            Collections.reverse(_actions);
        }
    }
}
