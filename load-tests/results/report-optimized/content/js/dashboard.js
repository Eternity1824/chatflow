/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
var showControllersOnly = false;
var seriesFilter = "";
var filtersOnlySampleSeries = true;

/*
 * Add header in statistics table to group metrics by category
 * format
 *
 */
function summaryTableHeader(header) {
    var newRow = header.insertRow(-1);
    newRow.className = "tablesorter-no-sort";
    var cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Requests";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 3;
    cell.innerHTML = "Executions";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 7;
    cell.innerHTML = "Response Times (ms)";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Throughput";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 2;
    cell.innerHTML = "Network (KB/sec)";
    newRow.appendChild(cell);
}

/*
 * Populates the table identified by id parameter with the specified data and
 * format
 *
 */
function createTable(table, info, formatter, defaultSorts, seriesIndex, headerCreator) {
    var tableRef = table[0];

    // Create header and populate it with data.titles array
    var header = tableRef.createTHead();

    // Call callback is available
    if(headerCreator) {
        headerCreator(header);
    }

    var newRow = header.insertRow(-1);
    for (var index = 0; index < info.titles.length; index++) {
        var cell = document.createElement('th');
        cell.innerHTML = info.titles[index];
        newRow.appendChild(cell);
    }

    var tBody;

    // Create overall body if defined
    if(info.overall){
        tBody = document.createElement('tbody');
        tBody.className = "tablesorter-no-sort";
        tableRef.appendChild(tBody);
        var newRow = tBody.insertRow(-1);
        var data = info.overall.data;
        for(var index=0;index < data.length; index++){
            var cell = newRow.insertCell(-1);
            cell.innerHTML = formatter ? formatter(index, data[index]): data[index];
        }
    }

    // Create regular body
    tBody = document.createElement('tbody');
    tableRef.appendChild(tBody);

    var regexp;
    if(seriesFilter) {
        regexp = new RegExp(seriesFilter, 'i');
    }
    // Populate body with data.items array
    for(var index=0; index < info.items.length; index++){
        var item = info.items[index];
        if((!regexp || filtersOnlySampleSeries && !info.supportsControllersDiscrimination || regexp.test(item.data[seriesIndex]))
                &&
                (!showControllersOnly || !info.supportsControllersDiscrimination || item.isController)){
            if(item.data.length > 0) {
                var newRow = tBody.insertRow(-1);
                for(var col=0; col < item.data.length; col++){
                    var cell = newRow.insertCell(-1);
                    cell.innerHTML = formatter ? formatter(col, item.data[col]) : item.data[col];
                }
            }
        }
    }

    // Add support of columns sort
    table.tablesorter({sortList : defaultSorts});
}

$(document).ready(function() {

    // Customize table sorter default options
    $.extend( $.tablesorter.defaults, {
        theme: 'blue',
        cssInfoBlock: "tablesorter-no-sort",
        widthFixed: true,
        widgets: ['zebra']
    });

    var data = {"OkPercent": 99.81, "KoPercent": 0.19};
    var dataset = [
        {
            "label" : "FAIL",
            "data" : data.KoPercent,
            "color" : "#FF6347"
        },
        {
            "label" : "PASS",
            "data" : data.OkPercent,
            "color" : "#9ACD32"
        }];
    $.plot($("#flot-requests-summary"), dataset, {
        series : {
            pie : {
                show : true,
                radius : 1,
                label : {
                    show : true,
                    radius : 3 / 4,
                    formatter : function(label, series) {
                        return '<div style="font-size:8pt;text-align:center;padding:2px;color:white;">'
                            + label
                            + '<br/>'
                            + Math.round10(series.percent, -2)
                            + '%</div>';
                    },
                    background : {
                        opacity : 0.5,
                        color : '#000'
                    }
                }
            }
        },
        legend : {
            show : true
        }
    });

    // Creates APDEX table
    createTable($("#apdexTable"), {"supportsControllersDiscrimination": true, "overall": {"data": [0.9981, 500, 1500, "Total"], "isController": false}, "titles": ["Apdex", "T (Toleration threshold)", "F (Frustration threshold)", "Label"], "items": [{"data": [0.9978169605373636, 500, 1500, "GET Room Messages 15min"], "isController": false}, {"data": [0.9981306081754736, 500, 1500, "GET User History Repeated"], "isController": false}, {"data": [0.9980372914622179, 500, 1500, "WS Open Connection"], "isController": false}, {"data": [0.9982409850483729, 500, 1500, "GET User Messages 15min"], "isController": false}, {"data": [0.9982394366197183, 500, 1500, "GET User Messages 5min"], "isController": false}, {"data": [0.9987209004860578, 500, 1500, "WS JOIN"], "isController": false}, {"data": [0.9973248620632001, 500, 1500, "GET User Rooms"], "isController": false}, {"data": [0.9972080801445229, 500, 1500, "GET Active Users"], "isController": false}, {"data": [0.9983459890233817, 500, 1500, "WS TEXT"], "isController": false}, {"data": [0.9982706683030236, 500, 1500, "GET Room Messages 5min"], "isController": false}, {"data": [0.9982855990318676, 500, 1500, "GET Room History Repeated"], "isController": false}]}, function(index, item){
        switch(index){
            case 0:
                item = item.toFixed(3);
                break;
            case 1:
            case 2:
                item = formatDuration(item);
                break;
        }
        return item;
    }, [[0, 0]], 3);

    // Create statistics table
    createTable($("#statisticsTable"), {"supportsControllersDiscrimination": true, "overall": {"data": ["Total", 100000, 190, 0.19, 180.32270999999963, 144, 461, 157.0, 386.0, 395.0, 445.0, 5547.234703500305, 41493.204312454924, 1462.4909424058355], "isController": false}, "titles": ["Label", "#Samples", "FAIL", "Error %", "Average", "Min", "Max", "Median", "90th pct", "95th pct", "99th pct", "Transactions/s", "Received", "Sent"], "items": [{"data": ["GET Room Messages 15min", 11910, 26, 0.218303946263644, 180.09185558354326, 144, 461, 157.0, 168.0, 395.0, 445.0, 663.0295607637922, 6165.236571355007, 175.31104518663363], "isController": false}, {"data": ["GET User History Repeated", 8024, 15, 0.18693918245264207, 179.59633599202417, 144, 461, 157.0, 167.0, 395.0, 446.0, 445.8025445858103, 4133.923889868882, 117.79213428523808], "isController": false}, {"data": ["WS Open Connection", 3057, 6, 0.19627085377821393, 180.9391560353288, 144, 460, 157.0, 387.0, 395.0, 444.0, 169.60719041278296, 31.835482377663116, 44.94874889036839], "isController": false}, {"data": ["GET User Messages 15min", 7959, 14, 0.1759014951627089, 179.933785651464, 144, 461, 157.0, 168.0, 395.0, 445.0, 444.01673640167365, 4157.696783472803, 116.85331677126918], "isController": false}, {"data": ["GET User Messages 5min", 11928, 21, 0.176056338028169, 180.50653923541265, 144, 461, 157.0, 386.0, 395.0, 446.0, 663.6990874693969, 6208.138767075172, 175.00291251669262], "isController": false}, {"data": ["WS JOIN", 3909, 5, 0.12790995139421846, 182.5405474545921, 144, 460, 157.0, 387.0, 395.0, 442.0, 217.1907989776642, 40.306182023697076, 57.43851096302367], "isController": false}, {"data": ["GET User Rooms", 5981, 16, 0.2675137936799866, 180.57247951847464, 144, 459, 157.0, 386.0, 395.0, 439.0, 331.9642559804629, 3093.2232622315037, 87.64053567464062], "isController": false}, {"data": ["GET Active Users", 6089, 17, 0.27919198554770897, 179.99638692724514, 144, 460, 157.0, 168.0, 395.0, 441.0, 339.18226381461676, 3183.743073978526, 88.98185961174242], "isController": false}, {"data": ["WS TEXT", 13301, 22, 0.16540109766182995, 179.9411322456959, 144, 461, 157.0, 168.0, 395.0, 444.0, 737.9604971149578, 137.1670455018309, 194.54399342543275], "isController": false}, {"data": ["GET Room Messages 5min", 17926, 31, 0.17293316969764588, 180.42608501617764, 144, 461, 157.0, 386.0, 396.0, 446.0, 995.2806618177781, 9300.520775498308, 262.58459457067903], "isController": false}, {"data": ["GET Room History Repeated", 9916, 17, 0.17144009681323114, 180.58914885034258, 144, 461, 157.0, 386.0, 396.0, 445.0, 551.5323432893931, 5149.207278282997, 144.5628080851271], "isController": false}]}, function(index, item){
        switch(index){
            // Errors pct
            case 3:
                item = item.toFixed(2) + '%';
                break;
            // Mean
            case 4:
            // Mean
            case 7:
            // Median
            case 8:
            // Percentile 1
            case 9:
            // Percentile 2
            case 10:
            // Percentile 3
            case 11:
            // Throughput
            case 12:
            // Kbytes/s
            case 13:
            // Sent Kbytes/s
                item = item.toFixed(2);
                break;
        }
        return item;
    }, [[0, 0]], 0, summaryTableHeader);

    // Create error table
    createTable($("#errorsTable"), {"supportsControllersDiscrimination": false, "titles": ["Type of error", "Number of errors", "% in errors", "% in all samples"], "items": [{"data": ["502/Server Error", 52, 27.36842105263158, 0.052], "isController": false}, {"data": ["500/Server Error", 51, 26.842105263157894, 0.051], "isController": false}, {"data": ["408/Server Error", 41, 21.57894736842105, 0.041], "isController": false}, {"data": ["504/Server Error", 46, 24.210526315789473, 0.046], "isController": false}]}, function(index, item){
        switch(index){
            case 2:
            case 3:
                item = item.toFixed(2) + '%';
                break;
        }
        return item;
    }, [[1, 1]]);

        // Create top5 errors by sampler
    createTable($("#top5ErrorsBySamplerTable"), {"supportsControllersDiscrimination": false, "overall": {"data": ["Total", 100000, 190, "502/Server Error", 52, "500/Server Error", 51, "504/Server Error", 46, "408/Server Error", 41, "", ""], "isController": false}, "titles": ["Sample", "#Samples", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors"], "items": [{"data": ["GET Room Messages 15min", 11910, 26, "500/Server Error", 8, "408/Server Error", 8, "502/Server Error", 6, "504/Server Error", 4, "", ""], "isController": false}, {"data": ["GET User History Repeated", 8024, 15, "502/Server Error", 5, "504/Server Error", 4, "500/Server Error", 3, "408/Server Error", 3, "", ""], "isController": false}, {"data": ["WS Open Connection", 3057, 6, "500/Server Error", 2, "408/Server Error", 2, "502/Server Error", 1, "504/Server Error", 1, "", ""], "isController": false}, {"data": ["GET User Messages 15min", 7959, 14, "500/Server Error", 6, "502/Server Error", 5, "408/Server Error", 3, "", "", "", ""], "isController": false}, {"data": ["GET User Messages 5min", 11928, 21, "500/Server Error", 6, "504/Server Error", 6, "502/Server Error", 5, "408/Server Error", 4, "", ""], "isController": false}, {"data": ["WS JOIN", 3909, 5, "500/Server Error", 2, "504/Server Error", 2, "408/Server Error", 1, "", "", "", ""], "isController": false}, {"data": ["GET User Rooms", 5981, 16, "504/Server Error", 6, "500/Server Error", 4, "502/Server Error", 3, "408/Server Error", 3, "", ""], "isController": false}, {"data": ["GET Active Users", 6089, 17, "504/Server Error", 6, "502/Server Error", 5, "500/Server Error", 4, "408/Server Error", 2, "", ""], "isController": false}, {"data": ["WS TEXT", 13301, 22, "502/Server Error", 7, "504/Server Error", 6, "408/Server Error", 5, "500/Server Error", 4, "", ""], "isController": false}, {"data": ["GET Room Messages 5min", 17926, 31, "502/Server Error", 11, "408/Server Error", 9, "504/Server Error", 7, "500/Server Error", 4, "", ""], "isController": false}, {"data": ["GET Room History Repeated", 9916, 17, "500/Server Error", 8, "502/Server Error", 4, "504/Server Error", 4, "408/Server Error", 1, "", ""], "isController": false}]}, function(index, item){
        return item;
    }, [[0, 0]], 0);

});
