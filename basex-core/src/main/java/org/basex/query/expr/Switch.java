package org.basex.query.expr;

import static org.basex.query.QueryText.*;

import java.util.*;
import java.util.function.*;

import org.basex.data.*;
import org.basex.query.*;
import org.basex.query.CompileContext.*;
import org.basex.query.expr.CmpG.*;
import org.basex.query.iter.*;
import org.basex.query.util.*;
import org.basex.query.util.list.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.seq.*;
import org.basex.query.value.type.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * Switch expression.
 *
 * @author BaseX Team 2005-20, BSD License
 * @author Christian Gruen
 */
public final class Switch extends ParseExpr {
  /** Condition. */
  private Expr cond;
  /** Case groups. */
  private SwitchGroup[] groups;

  /**
   * Constructor.
   * @param info input info
   * @param cond condition
   * @param groups case groups (last one is default case)
   */
  public Switch(final InputInfo info, final Expr cond, final SwitchGroup[] groups) {
    super(info, SeqType.ITEM_ZM);
    this.cond = cond;
    this.groups = groups;
  }

  @Override
  public void checkUp() throws QueryException {
    checkNoUp(cond);
    for(final SwitchGroup group : groups) group.checkUp();
    // check if none or all return expressions are updating
    final int gl = groups.length;
    final Expr[] tmp = new Expr[gl];
    for(int g = 0; g < gl; g++) tmp[g] = groups[g].exprs[0];
    checkAllUp(tmp);
  }

  @Override
  public Expr compile(final CompileContext cc) throws QueryException {
    cond = cond.compile(cc);
    for(final SwitchGroup group : groups) group.compile(cc);
    return optimize(cc);
  }

  @Override
  public Expr optimize(final CompileContext cc) throws QueryException {
    cond = cond.simplifyFor(Simplify.ATOM, cc);

    // check if expression can be pre-evaluated
    final Expr expr = opt(cc);
    if(expr != this) return cc.replaceWith(this, expr);

    // combine types of return expressions
    SeqType st = groups[0].exprs[0].seqType();
    final int gl = groups.length;
    for(int g = 1; g < gl; g++) st = st.union(groups[g].exprs[0].seqType());
    exprType.assign(st);
    return this;
  }

  @Override
  public Expr simplifyFor(final Simplify mode, final CompileContext cc) throws QueryException {
    boolean changed = false;
    for(final SwitchGroup group : groups) {
      changed |= group.simplify(mode, cc);
    }
    return changed ? optimize(cc) : super.simplifyFor(mode, cc);
  }

  /**
   * Optimizes the expression.
   * @param cc compilation context
   * @return optimized or original expression
   * @throws QueryException query exception
   */
  private Expr opt(final CompileContext cc) throws QueryException {
    // cached switch cases
    final ExprList cases = new ExprList();
    final Item item = cond instanceof Value ? cond.atomItem(cc.qc, info) : Empty.VALUE;
    final ArrayList<SwitchGroup> tmpGroups = new ArrayList<>();
    for(final SwitchGroup group : groups) {
      final int el = group.exprs.length;
      final Expr rtrn = group.exprs[0];
      final ExprList list = new ExprList(el).add(rtrn);
      for(int e = 1; e < el; e++) {
        final Expr expr = group.exprs[e];
        if(cond instanceof Value && expr instanceof Value) {
          // includes check for empty sequence (null reference)
          final Item cs = expr.atomItem(cc.qc, info);
          if(item == cs || cs != Empty.VALUE && item != Empty.VALUE && item.equiv(cs, null, info))
            return rtrn;
          cc.info(OPTREMOVE_X_X, expr, (Supplier<?>) this::description);
        } else if(cases.contains(expr)) {
          // case has already been checked before
          cc.info(OPTREMOVE_X_X, expr, (Supplier<?>) this::description);
        } else {
          cases.add(expr);
          list.add(expr);
        }
      }
      // build list of branches (add those with case left, or the default branch)
      if(list.size() > 1 || el == 1) {
        group.exprs = list.finish();
        tmpGroups.add(group);
      }
    }

    if(tmpGroups.size() != groups.length) {
      // branches have changed
      groups = tmpGroups.toArray(new SwitchGroup[0]);
      cc.info(OPTSIMPLE_X_X, (Supplier<?>) this::description, this);
    }

    Expr expr = simplify();
    if(expr == this) expr = toIf(cc);
    return expr;
  }

  /**
   * Simplifies a switch expression with identical branches.
   * @return new or original expression
   */
  private Expr simplify() {
    final Expr expr = groups[0].exprs[0];
    for(int g = groups.length - 1; g >= 1; g--) {
      if(!expr.equals(groups[g].exprs[0])) return this;
    }
    return expr;
  }

  /**
   * Rewrites the switch to an if expression.
   * @param cc compilation context
   * @return new or original expression
   * @throws QueryException query exception
   */
  private Expr toIf(final CompileContext cc) throws QueryException {
    if(groups.length != 2) return this;

    final SeqType st = cond.seqType();
    final boolean string = st.type.isStringOrUntyped(), dec = st.type.instanceOf(AtomType.DEC);
    if(!st.one() || !(string || dec)) return this;

    final Expr[] exprs = groups[0].exprs;
    for(int e = exprs.length - 1; e >= 1; e--) {
      final SeqType mt = exprs[e].seqType();
      if(!mt.one() || !(
        string && mt.type.isStringOrUntyped() ||
        dec && mt.type.instanceOf(AtomType.DEC)
      )) return this;
    }

    final List list = new List(groups[0].info, Arrays.copyOfRange(exprs, 1, exprs.length));
    final CmpG cmp = new CmpG(cond, list.optimize(cc), OpG.EQ, null, null, groups[0].info);
    return new If(info, cmp.optimize(cc), groups[0].exprs[0], groups[1].exprs[0]).optimize(cc);
  }

  @Override
  public Iter iter(final QueryContext qc) throws QueryException {
    return expr(qc).iter(qc);
  }

  @Override
  public Value value(final QueryContext qc) throws QueryException {
    return expr(qc).value(qc);
  }

  @Override
  public Item item(final QueryContext qc, final InputInfo ii) throws QueryException {
    return expr(qc).item(qc, info);
  }

  /**
   * Tests the conditions and returns the expression to evaluate.
   * @param qc query context
   * @return case expression
   * @throws QueryException query exception
   */
  private Expr expr(final QueryContext qc) throws QueryException {
    final Item item = cond.atomItem(qc, info);
    for(final SwitchGroup group : groups) {
      if(group.match(item, qc)) return group.exprs[0];
    }
    throw Util.notExpected();
  }

  @Override
  public boolean has(final Flag... flags) {
    for(final SwitchGroup group : groups) {
      if(group.has(flags)) return true;
    }
    return cond.has(flags);
  }

  @Override
  public boolean inlineable(final Var var) {
    for(final SwitchGroup group : groups) {
      if(!group.inlineable(var)) return false;
    }
    return cond.inlineable(var);
  }

  @Override
  public VarUsage count(final Var var) {
    VarUsage max = VarUsage.NEVER, curr = VarUsage.NEVER;
    for(final SwitchGroup cs : groups) {
      curr = curr.plus(cs.countCases(var));
      max = max.max(curr.plus(cs.count(var)));
    }
    return max.plus(cond.count(var));
  }

  @Override
  public Expr inline(final ExprInfo ei, final Expr ex, final CompileContext cc)
      throws QueryException {
    boolean changed = inlineAll(ei, ex, groups, cc);
    final Expr inlined = cond.inline(ei, ex, cc);
    if(inlined != null) {
      changed = true;
      cond = inlined;
    }
    return changed ? optimize(cc) : null;
  }

  @Override
  public Expr copy(final CompileContext cc, final IntObjMap<Var> vm) {
    return copyType(new Switch(info, cond.copy(cc, vm), Arr.copyAll(cc, vm, groups)));
  }

  @Override
  public boolean vacuous() {
    return ((Checks<SwitchGroup>) group -> group.exprs[0].vacuous()).all(groups);
  }

  @Override
  public boolean ddo() {
    return ((Checks<SwitchGroup>) group -> group.exprs[0].ddo()).all(groups);
  }

  @Override
  public void markTailCalls(final CompileContext cc) {
    for(final SwitchGroup group : groups) group.markTailCalls(cc);
  }

  @Override
  public Data data() {
    final ExprList list = new ExprList(groups.length);
    for(final SwitchGroup group : groups) list.add(group.exprs[0]);
    return data(list.finish());
  }

  @Override
  public boolean accept(final ASTVisitor visitor) {
    return cond.accept(visitor) && visitAll(visitor, groups);
  }

  @Override
  public int exprSize() {
    int size = 1;
    for(final Expr group : groups) size += group.exprSize();
    return size;
  }

  @Override
  public boolean equals(final Object obj) {
    if(this == obj) return true;
    if(!(obj instanceof Switch)) return false;
    final Switch s = (Switch) obj;
    return cond.equals(s.cond) && Array.equals(groups, s.groups);
  }

  @Override
  public void plan(final QueryPlan plan) {
    plan.add(plan.create(this), cond, groups);
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(SWITCH + PAREN1 + cond + PAREN2);
    for(final SwitchGroup group : groups) sb.append(group);
    return sb.toString();
  }
}
