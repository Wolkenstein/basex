package org.basex.query.func.fn;

import org.basex.query.*;
import org.basex.query.expr.*;
import org.basex.query.func.*;
import org.basex.query.value.*;
import org.basex.query.value.type.*;
import org.basex.util.*;

/**
 * Function implementation.
 *
 * @author BaseX Team 2005-20, BSD License
 * @author Christian Gruen
 */
public final class FnFilter extends StandardFunc {
  @Override
  public Value value(final QueryContext qc) throws QueryException {
    throw Util.notExpected();
  }

  @Override
  protected Expr opt(final CompileContext cc) throws QueryException {
    final Expr items = exprs[0];
    final SeqType st = items.seqType();
    if(st.zero()) return items;

    // create filter expression
    Expr pred = coerceFunc(exprs[1], cc, SeqType.BLN_O, st.with(Occ.ONE));
    cc.pushFocus(items);
    try {
      pred = new ContextValue(info).optimize(cc);
      pred = new DynFuncCall(info, sc, exprs[1], pred).optimize(cc);
      pred = new TypeCheck(sc, info, pred, SeqType.BLN_O, true).optimize(cc);
    } finally {
      cc.removeFocus();
    }
    return Filter.get(info, items, cc.function(Function.BOOLEAN, info, pred)).optimize(cc);
  }
}
