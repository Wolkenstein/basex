package org.basex.query.expr;

import org.basex.query.*;
import org.basex.query.CompileContext.*;
import org.basex.query.func.*;
import org.basex.query.value.item.*;
import org.basex.query.value.type.*;
import org.basex.util.*;

/**
 * Logical expression, extended by {@link And} and {@link Or}.
 *
 * @author BaseX Team 2005-20, BSD License
 * @author Christian Gruen
 */
abstract class Logical extends Arr {
  /**
   * Constructor.
   * @param info input info
   * @param exprs expressions
   */
  Logical(final InputInfo info, final Expr[] exprs) {
    super(info, SeqType.BLN_O, exprs);
  }

  @Override
  public final Expr compile(final CompileContext cc) throws QueryException {
    final int el = exprs.length;
    for(int e = 0; e < el; e++) {
      try {
        exprs[e] = exprs[e].compile(cc);
      } catch(final QueryException qe) {
        // first expression is evaluated eagerly
        if(e == 0) throw qe;
        exprs[e] = cc.error(qe, exprs[e]);
      }
    }
    return optimize(cc);
  }

  /**
   * Optimizes the expression.
   * @param cc compilation context
   * @param or union or intersection
   * @return resulting expression
   * @throws QueryException query exception
   */
  final Expr optimize(final CompileContext cc, final boolean or) throws QueryException {
    simplifyAll(Simplify.EBV, cc);
    if(optimizeEbv(or, false, cc)) return cc.replaceWith(this, Bln.get(or));

    final int el = exprs.length;
    if(el == 0) return Bln.get(!or);
    if(el == 1) return cc.function(Function.BOOLEAN, info, exprs);
    return this;
  }

  @Override
  public final void markTailCalls(final CompileContext cc) {
    // if the last expression surely returns a boolean, we can jump to it
    final Expr last = exprs[exprs.length - 1];
    if(last.seqType().eq(SeqType.BLN_O)) last.markTailCalls(cc);
  }

  @Override
  public Expr inline(final ExprInfo ei, final Expr ex, final CompileContext cc)
      throws QueryException {
    boolean changed = false;
    final int el = exprs.length;
    for(int e = 0; e < el; e++) {
      try {
        final Expr inlined = exprs[e].inline(ei, ex, cc);
        if(inlined != null) {
          exprs[e] = inlined;
          changed = true;
        }
      } catch(final QueryException qe) {
        // first expression is evaluated eagerly
        if(e == 0) throw qe;

        // everything behind the error is dead anyway
        final Expr[] nw = new Expr[e + 1];
        Array.copy(exprs, e, nw);
        nw[e] = cc.error(qe, this);
        exprs = nw;
        changed = true;
        break;
      }
    }
    return changed ? optimize(cc) : null;
  }

  @Override
  public void plan(final QueryPlan plan) {
    plan.add(plan.create(this), exprs);
  }
}
