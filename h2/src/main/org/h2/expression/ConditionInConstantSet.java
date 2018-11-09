/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.expression;

import java.util.ArrayList;
import java.util.TreeSet;

import org.h2.engine.Database;
import org.h2.engine.Mode;
import org.h2.engine.Session;
import org.h2.index.IndexCondition;
import org.h2.message.DbException;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.value.ExtTypeInfo;
import org.h2.value.Value;
import org.h2.value.ValueBoolean;
import org.h2.value.ValueNull;

/**
 * Used for optimised IN(...) queries where the contents of the IN list are all
 * constant and of the same type.
 * <p>
 * Checking using a HashSet is has time complexity O(1), instead of O(n) for
 * checking using an array.
 */
public class ConditionInConstantSet extends Condition {

    private Expression left;
    private int queryLevel;
    private final ArrayList<Expression> valueList;
    private final TreeSet<Value> valueSet;
    private final int type;
    private ExtTypeInfo extTypeInfo;

    /**
     * Create a new IN(..) condition.
     *
     * @param session the session
     * @param left
     *            the expression before IN. Cannot have {@link Value#UNKNOWN}
     *            data type and {@link Value#ENUM} type is also supported only
     *            for {@link ExpressionColumn}.
     * @param valueList the value list (at least two elements)
     */
    public ConditionInConstantSet(Session session, Expression left, ArrayList<Expression> valueList) {
        this.left = left;
        this.valueList = valueList;
        Database database = session.getDatabase();
        this.valueSet = new TreeSet<>(database.getCompareMode());
        type = left.getType();
        Mode mode = database.getMode();
        if (type == Value.ENUM) {
            extTypeInfo = ((ExpressionColumn) left).getColumn().getExtTypeInfo();
            for (Expression expression : valueList) {
                valueSet.add(extTypeInfo.cast(expression.getValue(session)));
            }
        } else {
            for (Expression expression : valueList) {
                valueSet.add(expression.getValue(session).convertTo(type, mode));
            }
        }
    }

    @Override
    public Value getValue(Session session) {
        Value x = left.getValue(session);
        if (x == ValueNull.INSTANCE) {
            return x;
        }
        boolean result = valueSet.contains(x);
        if (!result) {
            boolean setHasNull = valueSet.contains(ValueNull.INSTANCE);
            if (setHasNull) {
                return ValueNull.INSTANCE;
            }
        }
        return ValueBoolean.get(result);
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level, int state) {
        left.mapColumns(resolver, level, state);
        this.queryLevel = Math.max(level, this.queryLevel);
    }

    @Override
    public Expression optimize(Session session) {
        left = left.optimize(session);
        return this;
    }

    @Override
    public void createIndexConditions(Session session, TableFilter filter) {
        if (!(left instanceof ExpressionColumn)) {
            return;
        }
        ExpressionColumn l = (ExpressionColumn) left;
        if (filter != l.getTableFilter()) {
            return;
        }
        if (session.getDatabase().getSettings().optimizeInList) {
            filter.addIndexCondition(IndexCondition.getInList(l, valueList));
        }
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        left.setEvaluatable(tableFilter, b);
    }

    @Override
    public StringBuilder getSQL(StringBuilder builder) {
        builder.append('(');
        left.getSQL(builder).append(" IN(");
        writeExpressions(builder, valueList);
        return builder.append("))");
    }

    @Override
    public void updateAggregate(Session session, int stage) {
        left.updateAggregate(session, stage);
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        if (!left.isEverything(visitor)) {
            return false;
        }
        switch (visitor.getType()) {
        case ExpressionVisitor.OPTIMIZABLE_AGGREGATE:
        case ExpressionVisitor.DETERMINISTIC:
        case ExpressionVisitor.READONLY:
        case ExpressionVisitor.INDEPENDENT:
        case ExpressionVisitor.EVALUATABLE:
        case ExpressionVisitor.SET_MAX_DATA_MODIFICATION_ID:
        case ExpressionVisitor.NOT_FROM_RESOLVER:
        case ExpressionVisitor.GET_DEPENDENCIES:
        case ExpressionVisitor.QUERY_COMPARABLE:
        case ExpressionVisitor.GET_COLUMNS1:
        case ExpressionVisitor.GET_COLUMNS2:
            return true;
        default:
            throw DbException.throwInternalError("type=" + visitor.getType());
        }
    }

    @Override
    public int getCost() {
        return left.getCost();
    }

    /**
     * Add an additional element if possible. Example: given two conditions
     * A IN(1, 2) OR A=3, the constant 3 is added: A IN(1, 2, 3).
     *
     * @param session the session
     * @param other the second condition
     * @return null if the condition was not added, or the new condition
     */
    Expression getAdditional(Session session, Comparison other) {
        Expression add = other.getIfEquals(left);
        if (add != null) {
            if (add.isConstant()) {
                valueList.add(add);
                if (type == Value.ENUM) {
                    valueSet.add(add.getValue(session).convertToEnum(extTypeInfo));
                } else {
                    valueSet.add(add.getValue(session).convertTo(type, session.getDatabase().getMode()));
                }
                return this;
            }
        }
        return null;
    }
}
