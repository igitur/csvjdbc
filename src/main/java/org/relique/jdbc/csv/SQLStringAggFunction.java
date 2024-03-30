/*
 *  CsvJdbc - a JDBC driver for CSV files
 *  Copyright (C) 2015  Simon Chenery
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.relique.jdbc.csv;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class SQLStringAggFunction extends AggregateFunction
{
	boolean isDistinct;
	Expression expression;
	Expression delimiter;
	ArrayList<Object> aggregateValues = new ArrayList<>();
	LinkedHashSet<Object> distinctAggregateValues = new LinkedHashSet<>();
	public SQLStringAggFunction(boolean isDistinct, Expression expression, Expression delimiter)
	{
		this.isDistinct = isDistinct;
		this.expression = expression;
		this.delimiter = delimiter;
	}
	@Override
	public Object eval(Map<String, Object> env) throws SQLException
	{
		Object stringAggregation = null;
		Object o = env.get(GROUPING_COLUMN_NAME);
		Object o2 = delimiter.eval(env);
		if (o != null)
		{
			StringBuffer sb = new StringBuffer();
			List groupRows = (List)o;
			for (int i = 0; i < groupRows.size(); i++)
			{
				o = expression.eval((Map)groupRows.get(i));
				if (o != null)
				{
					if (sb.length() > 0 && o2 != null)
						sb.append(o2);
					sb.append(o);
				}
			}
			stringAggregation = sb.toString();
		}
		else
		{
			StringBuffer sb = new StringBuffer();
			Iterator<Object> it = aggregateValues.iterator();
			while (it.hasNext())
			{
				if (sb.length() > 0 && o2 != null)
					sb.append(o2);
				sb.append(it.next().toString());
			}
			it = distinctAggregateValues.iterator();
			while (it.hasNext())
			{
				if (sb.length() > 0 && o2 != null)
					sb.append(o2);
				sb.append(it.next().toString());
			}
			stringAggregation = sb.toString();
		}
		return stringAggregation;
	}
	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder("STRING_AGG(");
		if (isDistinct)
			sb.append("DISTINCT ");
		sb.append(expression);
		sb.append(", ");
		sb.append(delimiter);
		sb.append(")");
		return sb.toString();
	}
	@Override
	public List<String> usedColumns(Set<String> availableColumns)
	{
		return List.copyOf(delimiter.usedColumns(availableColumns));
	}
	@Override
	public List<String> aggregateColumns(Set<String> availableColumns)
	{
		return List.copyOf(expression.usedColumns(availableColumns));
	}
	@Override
	public List<AggregateFunction> aggregateFunctions()
	{
		return List.of(this);
	}
	@Override
	public void resetAggregateFunctions()
	{
		distinctAggregateValues.clear();
		aggregateValues.clear();
	}
	@Override
	public void processRow(Map<String, Object> env) throws SQLException
	{
		/*
		 * Only consider non-null values.
		 */
		Object o = expression.eval(env);
		if (o != null)
		{
			if (isDistinct)
				distinctAggregateValues.add(o);
			else
				aggregateValues.add(o);
		}
	}
}